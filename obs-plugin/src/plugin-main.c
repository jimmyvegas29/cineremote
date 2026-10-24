/*
 * CineRemote Camera - native OBS source.
 *
 * Receives already-decoded video + audio from the companion "CineRemote" app through
 * shared memory (see cineremote-shm.h) and presents it as an async OBS input source. Windows, Linux, macOS:
 * Windows uses a named file mapping + event; Linux/macOS use POSIX shm_open and a 2 ms poll.
 * This is ordinary cooperating-process IPC between two first-party programs the user runs: the app
 * writes frames into a memory-mapped file it owns, and this plugin, chosen by the user from the
 * source list, maps the same file read-only and hands the frames to OBS. No window capture, no
 * re-encode. The same shared-memory pattern is used by OBS's own Game Capture.
 */
#include <obs-module.h>
#include <util/platform.h>
#include <util/bmem.h>
#include <stdio.h>
#include <string.h>
#include "cineremote-shm.h"

#ifdef _WIN32
#include <windows.h>
/* Native Win32 sync: util/threading.h pulls in pthread.h, which the Windows OBS SDK doesn't ship. */
EXPORT void os_set_thread_name(const char *name); /* from obs.dll (util/threading.h) */
typedef HANDLE map_handle_t;
typedef CRITICAL_SECTION lock_t;
#define lock_init(l) InitializeCriticalSection(l)
#define lock_free(l) DeleteCriticalSection(l)
#define lock_enter(l) EnterCriticalSection(l)
#define lock_leave(l) LeaveCriticalSection(l)
#define READ_FENCE() MemoryBarrier()
#else
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <util/threading.h>
typedef size_t map_handle_t; /* POSIX: the mapped length (needed for munmap) */
typedef pthread_mutex_t lock_t;
#define lock_init(l) pthread_mutex_init(l, NULL)
#define lock_free(l) pthread_mutex_destroy(l)
#define lock_enter(l) pthread_mutex_lock(l)
#define lock_leave(l) pthread_mutex_unlock(l)
/* x86 keeps loads in order; ARM (Apple Silicon) needs an acquire fence between reading the frame
 * index/sequence and reading the frame it points to. The publisher fences on its side too. */
#define READ_FENCE() __atomic_thread_fence(__ATOMIC_ACQUIRE)
#define POLL_MS 2 /* no named events on POSIX: poll the header (~1 ms average added latency) */
#endif

OBS_DECLARE_MODULE()
OBS_MODULE_USE_DEFAULT_LOCALE("cineremote-obs", "en-US")

#define PLUGIN_VERSION "1.3.0"
#define S_CAMERA "camera_id"
#define S_AUDIO "audio"
#define S_BUFFERED "buffered"
#define AUDIO_MAX_CATCHUP_FRAMES (BMCAM_AUDIO_RATE / 10) /* never hand OBS more than 100 ms of backlog */
#define WAIT_MS 10 /* bounded wait so several readers of one slot never starve each other */

#define blog(level, format, ...) blog(level, "[cineremote] " format, ##__VA_ARGS__)

MODULE_EXPORT const char *obs_module_description(void)
{
	return "CineRemote Camera: native source for phones running the Blackmagic Camera app, "
	       "fed by the CineRemote app.";
}

struct cineremote_src {
	obs_source_t *source;
#ifdef _WIN32
	HANDLE thread;
	HANDLE stop_event;
#else
	pthread_t thread;
	bool thread_ok;
	os_event_t *stop_event;
#endif

	lock_t lock;        /* guards wanted_id + audio_enabled */
	char wanted_id[64]; /* cam_id to attach to; "" = first available */
	bool audio_enabled;

	/* reader-thread-only state */
	map_handle_t map;
#ifdef _WIN32
	HANDLE evt;
#endif
	uint8_t *base;
	struct cineremote_header *hdr;
	int slot;
	uint32_t generation;
	uint64_t last_vseq;
	uint64_t last_apos;
	bool showing_video;
	uint64_t last_scan_ns;
	uint64_t frames_out;

	float color_matrix[2][16]; /* [0] limited, [1] full range (BT.709) */
	float color_min[2][3];
	float color_max[2][3];

	int16_t *audio_tmp; /* assembly buffer for audio ring reads */
};

#ifdef _WIN32
static void map_name_w(const char *fmt, int n, wchar_t *w, size_t cch)
{
	char a[80];
	snprintf(a, sizeof(a), fmt, n);
	MultiByteToWideChar(CP_UTF8, 0, a, -1, w, (int)cch);
}

static uint8_t *map_raw(int n, map_handle_t *out_map)
{
	wchar_t w[80];
	map_name_w("Local\\CineRemote_v1_slot%d", n, w, 80);
	HANDLE m = OpenFileMappingW(FILE_MAP_READ, FALSE, w);
	if (!m)
		return NULL;
	uint8_t *p = (uint8_t *)MapViewOfFile(m, FILE_MAP_READ, 0, 0, 0);
	MEMORY_BASIC_INFORMATION mbi;
	if (!p || !VirtualQuery(p, &mbi, sizeof(mbi)) || mbi.RegionSize < BMCAM_TOTAL_SIZE) {
		if (p)
			UnmapViewOfFile(p);
		CloseHandle(m);
		return NULL;
	}
	*out_map = m;
	return p;
}

static void unmap_slot(uint8_t *p, map_handle_t m)
{
	if (p)
		UnmapViewOfFile(p);
	if (m)
		CloseHandle(m);
}
#else
static uint8_t *map_raw(int n, map_handle_t *out_map)
{
	char name[64];
	snprintf(name, sizeof(name), "/cineremote_v1_s%d", n);
	int fd = shm_open(name, O_RDONLY, 0);
	if (fd < 0)
		return NULL;
	struct stat st;
	if (fstat(fd, &st) != 0 || (uint64_t)st.st_size < BMCAM_TOTAL_SIZE) {
		close(fd);
		return NULL;
	}
	void *p = mmap(NULL, (size_t)BMCAM_TOTAL_SIZE, PROT_READ, MAP_SHARED, fd, 0);
	close(fd); /* the mapping keeps the object alive */
	if (p == MAP_FAILED)
		return NULL;
	*out_map = (size_t)BMCAM_TOTAL_SIZE;
	return (uint8_t *)p;
}

static void unmap_slot(uint8_t *p, map_handle_t m)
{
	if (p)
		munmap(p, m);
}
#endif

/* Map one slot read-only; validate header and mapping size. Returns base pointer or NULL. */
static uint8_t *map_slot(int n, map_handle_t *out_map)
{
	map_handle_t m = 0;
	uint8_t *p = map_raw(n, &m);
	if (!p)
		return NULL;
	struct cineremote_header *h = (struct cineremote_header *)p;
	if (h->magic != BMCAM_MAGIC || h->version != BMCAM_VERSION || h->audio_rate != BMCAM_AUDIO_RATE ||
	    h->audio_channels != BMCAM_AUDIO_CH || h->audio_ring_frames != BMCAM_AUDIO_RING_FRAMES) {
		unmap_slot(p, m);
		return NULL;
	}
	*out_map = m;
	return p;
}

static bool header_live(const struct cineremote_header *h)
{
	uint64_t now = os_gettime_ns(), hb = h->heartbeat_ns;
	return h->active && hb && (now < hb || now - hb < BMCAM_STALE_NS);
}

static void detach(struct cineremote_src *s)
{
#ifdef _WIN32
	if (s->evt) {
		CloseHandle(s->evt);
		s->evt = NULL;
	}
#endif
	if (s->base)
		unmap_slot(s->base, s->map);
	s->base = NULL;
	s->hdr = NULL;
	s->map = 0;
	s->slot = -1;
}

static void clear_video(struct cineremote_src *s)
{
	if (s->showing_video) {
		obs_source_output_video(s->source, NULL); /* "no signal" */
		s->showing_video = false;
	}
}

static void get_wanted(struct cineremote_src *s, char want[64])
{
	lock_enter(&s->lock);
	memcpy(want, s->wanted_id, 64);
	lock_leave(&s->lock);
	want[63] = 0;
}

/* Find the live slot for the wanted camera ("" = any). Picks the freshest heartbeat, so after an
 * app restart (new slot, old one left behind) we follow the camera to its new slot. */
static bool try_attach(struct cineremote_src *s)
{
	char want[64];
	get_wanted(s, want);
	int best = -1;
	uint64_t best_hb = 0;
	for (int n = 0; n < BMCAM_MAX_SLOTS; n++) {
		map_handle_t m = 0;
		uint8_t *p = map_slot(n, &m);
		if (!p)
			continue;
		struct cineremote_header *h = (struct cineremote_header *)p;
		bool match = header_live(h) && (want[0] == 0 || strncmp(h->cam_id, want, sizeof(h->cam_id)) == 0);
		uint64_t hb = h->heartbeat_ns;
		unmap_slot(p, m);
		if (match && hb > best_hb) {
			best = n;
			best_hb = hb;
		}
	}
	if (best < 0)
		return false;
	map_handle_t m = 0;
	uint8_t *p = map_slot(best, &m);
	if (!p)
		return false;
	s->map = m;
	s->base = p;
	s->hdr = (struct cineremote_header *)p;
	s->slot = best;
#ifdef _WIN32
	wchar_t evtw[80];
	map_name_w("Local\\CineRemote_v1_slot%d_evt", best, evtw, 80);
	s->evt = OpenEventW(SYNCHRONIZE, FALSE, evtw);
#endif
	s->generation = s->hdr->generation;
	s->last_vseq = 0;
	s->last_apos = s->hdr->audio_write_frames; /* start live, no backlog */
	s->frames_out = 0;
	blog(LOG_INFO, "attached to slot %d (%s)", best, s->hdr->name);
	return true;
}

static void emit_video(struct cineremote_src *s)
{
	struct cineremote_header *h = s->hdr;
	uint64_t seq = h->video_seq;
	if (seq == s->last_vseq)
		return;
	s->last_vseq = seq;
	uint32_t idx = h->video_index;
	READ_FENCE(); /* the frame behind idx is complete once idx/seq are visible */
	if (idx >= 3)
		return;
	struct cineremote_vframe vf = h->vf[idx]; /* copy descriptor */
	if (vf.format != 1 || vf.width < 2 || vf.height < 2 || (uint64_t)vf.width * vf.height > BMCAM_MAX_PIXELS)
		return;
	uint32_t cw = (vf.width + 1) / 2, ch = (vf.height + 1) / 2;
	uint64_t need = (uint64_t)vf.stride_y * vf.height + (uint64_t)vf.stride_u * ch + (uint64_t)vf.stride_v * ch;
	if (vf.stride_y < vf.width || vf.stride_u < cw || vf.stride_v < cw || need > BMCAM_VIDEO_BUF_SIZE)
		return; /* corrupt or torn descriptor: never let OBS read past the buffer */

	uint8_t *vbase = s->base + BMCAM_HEADER_SIZE + BMCAM_AUDIO_BYTES + (uint64_t)idx * BMCAM_VIDEO_BUF_SIZE;
	int full = h->color_range == 2 ? 1 : 0;

	struct obs_source_frame frame = {0};
	frame.format = VIDEO_FORMAT_I420;
	frame.width = vf.width;
	frame.height = vf.height;
	frame.timestamp = vf.pts_ns;
	frame.full_range = full != 0;
	frame.data[0] = vbase;
	frame.data[1] = vbase + (size_t)vf.stride_y * vf.height;
	frame.data[2] = frame.data[1] + (size_t)vf.stride_u * ch;
	frame.linesize[0] = vf.stride_y;
	frame.linesize[1] = vf.stride_u;
	frame.linesize[2] = vf.stride_v;
	memcpy(frame.color_matrix, s->color_matrix[full], sizeof(frame.color_matrix));
	memcpy(frame.color_range_min, s->color_min[full], sizeof(frame.color_range_min));
	memcpy(frame.color_range_max, s->color_max[full], sizeof(frame.color_range_max));

	/* obs copies the frame data synchronously, so pointing into the mapped buffer is safe. */
	obs_source_output_video(s->source, &frame);
	s->showing_video = true;
	if (++s->frames_out == 1)
		blog(LOG_INFO, "first video frame %ux%u I420 from slot %d", vf.width, vf.height, s->slot);
}

static void emit_audio(struct cineremote_src *s)
{
	bool want;
	lock_enter(&s->lock);
	want = s->audio_enabled;
	lock_leave(&s->lock);

	struct cineremote_header *h = s->hdr;
	uint64_t write = h->audio_write_frames;
	READ_FENCE();
	if (!want || write <= s->last_apos) {
		if (write > s->last_apos || !want)
			s->last_apos = write; /* stay current so re-enabling doesn't dump a backlog */
		return;
	}
	uint64_t avail = write - s->last_apos;
	if (avail > AUDIO_MAX_CATCHUP_FRAMES) {
		/* fell behind (OBS stall): drop the old audio instead of bursting it out late */
		s->last_apos = write - AUDIO_MAX_CATCHUP_FRAMES;
		avail = AUDIO_MAX_CATCHUP_FRAMES;
	}
	uint32_t frames = (uint32_t)avail;
	int16_t *ring = (int16_t *)(s->base + BMCAM_HEADER_SIZE);
	uint64_t start = s->last_apos % BMCAM_AUDIO_RING_FRAMES;
	uint64_t first = BMCAM_AUDIO_RING_FRAMES - start;
	if (first > frames)
		first = frames;
	memcpy(s->audio_tmp, ring + start * BMCAM_AUDIO_CH, (size_t)first * BMCAM_AUDIO_CH * sizeof(int16_t));
	if (frames > first)
		memcpy(s->audio_tmp + first * BMCAM_AUDIO_CH, ring,
		       (size_t)(frames - first) * BMCAM_AUDIO_CH * sizeof(int16_t));

	/* audio_pts_ns is the timestamp of the sample at audio_write_frames (the end of this chunk). */
	uint64_t end_pts = h->audio_pts_ns;
	uint64_t span_ns = (uint64_t)frames * 1000000000ull / BMCAM_AUDIO_RATE;
	struct obs_source_audio a = {0};
	a.data[0] = (const uint8_t *)s->audio_tmp;
	a.frames = frames;
	a.speakers = SPEAKERS_STEREO;
	a.format = AUDIO_FORMAT_16BIT;
	a.samples_per_sec = BMCAM_AUDIO_RATE;
	a.timestamp = end_pts > span_ns ? end_pts - span_ns : end_pts;
	obs_source_output_audio(s->source, &a);
	s->last_apos = write;
}

/* Sleep until stop (returns true), new data (Windows event) or the timeout. */
static bool wait_stop(struct cineremote_src *s, unsigned ms, bool data_event)
{
#ifdef _WIN32
	HANDLE waits[2] = {s->stop_event, s->evt};
	return WaitForMultipleObjects(data_event && s->evt ? 2 : 1, waits, FALSE, ms) == WAIT_OBJECT_0;
#else
	UNUSED_PARAMETER(data_event);
	return os_event_timedwait(s->stop_event, ms) == 0;
#endif
}

#ifdef _WIN32
static DWORD WINAPI reader_thread(LPVOID arg)
#else
static void *reader_thread(void *arg)
#endif
{
	struct cineremote_src *s = (struct cineremote_src *)arg;
	os_set_thread_name("cineremote-reader");
	for (;;) {
		if (!s->base) {
			clear_video(s);
			if (!try_attach(s)) {
				if (wait_stop(s, 500, false))
					break;
				continue;
			}
		}
#ifdef _WIN32
		if (wait_stop(s, WAIT_MS, true))
			break;
#else
		if (wait_stop(s, POLL_MS, false))
			break;
#endif

		struct cineremote_header *h = s->hdr;
		char want[64];
		get_wanted(s, want);
		if (want[0] && strncmp(h->cam_id, want, sizeof(h->cam_id)) != 0) {
			detach(s); /* user picked a different camera */
			continue;
		}
		if (h->generation != s->generation) {
			/* publisher restarted on this slot: resync counters */
			s->generation = h->generation;
			s->last_vseq = 0;
			s->last_apos = h->audio_write_frames;
		}
		if (!header_live(h)) {
			/* app closed or crashed: show "no signal" and look for the camera elsewhere */
			clear_video(s);
			uint64_t now = os_gettime_ns();
			if (!h->active || now - s->last_scan_ns > 1000000000ull) {
				s->last_scan_ns = now;
				detach(s);
			}
			continue;
		}
		emit_video(s);
		emit_audio(s);
	}
	detach(s);
	return 0;
}

/* ---------------------------------------------------------------- obs source callbacks */
static const char *src_get_name(void *unused)
{
	UNUSED_PARAMETER(unused);
	return "CineRemote Camera";
}

static void src_update(void *data, obs_data_t *settings)
{
	struct cineremote_src *s = data;
	const char *cam = obs_data_get_string(settings, S_CAMERA);
	bool audio = obs_data_get_bool(settings, S_AUDIO);
	/* Frames are stamped with their arrival time, so OBS's buffered mode has no jitter to smooth;
	 * it only queues frames (measured: latency crept to ~1.2 s, the 30-frame async queue cap).
	 * Unbuffered shows the newest frame each tick, like the Video Capture Device source. */
	obs_source_set_async_unbuffered(s->source, !obs_data_get_bool(settings, S_BUFFERED));
	lock_enter(&s->lock);
	snprintf(s->wanted_id, sizeof(s->wanted_id), "%s", cam ? cam : "");
	s->audio_enabled = audio;
	lock_leave(&s->lock);
}

static void *src_create(obs_data_t *settings, obs_source_t *source)
{
	struct cineremote_src *s = bzalloc(sizeof(struct cineremote_src));
	s->source = source;
	s->slot = -1;
	lock_init(&s->lock);
	s->audio_tmp = bmalloc((size_t)AUDIO_MAX_CATCHUP_FRAMES * BMCAM_AUDIO_CH * sizeof(int16_t));
	video_format_get_parameters(VIDEO_CS_709, VIDEO_RANGE_PARTIAL, s->color_matrix[0], s->color_min[0],
				    s->color_max[0]);
	video_format_get_parameters(VIDEO_CS_709, VIDEO_RANGE_FULL, s->color_matrix[1], s->color_min[1],
				    s->color_max[1]);
	src_update(s, settings);
#ifdef _WIN32
	s->stop_event = CreateEventW(NULL, TRUE, FALSE, NULL);
	s->thread = s->stop_event ? CreateThread(NULL, 0, reader_thread, s, 0, NULL) : NULL;
	if (!s->thread)
		blog(LOG_ERROR, "failed to start reader thread");
#else
	if (os_event_init(&s->stop_event, OS_EVENT_TYPE_MANUAL) == 0)
		s->thread_ok = pthread_create(&s->thread, NULL, reader_thread, s) == 0;
	if (!s->thread_ok)
		blog(LOG_ERROR, "failed to start reader thread");
#endif
	return s;
}

static void src_destroy(void *data)
{
	struct cineremote_src *s = data;
#ifdef _WIN32
	if (s->thread) {
		SetEvent(s->stop_event); /* wakes the reader immediately */
		WaitForSingleObject(s->thread, INFINITE);
		CloseHandle(s->thread);
	}
	if (s->stop_event)
		CloseHandle(s->stop_event);
#else
	if (s->thread_ok) {
		os_event_signal(s->stop_event);
		pthread_join(s->thread, NULL);
	}
	if (s->stop_event)
		os_event_destroy(s->stop_event);
#endif
	lock_free(&s->lock);
	bfree(s->audio_tmp);
	bfree(s);
}

static bool refresh_clicked(obs_properties_t *props, obs_property_t *p, void *data)
{
	UNUSED_PARAMETER(props);
	UNUSED_PARAMETER(p);
	UNUSED_PARAMETER(data);
	return true; /* returning true asks OBS to rebuild the properties UI */
}

static obs_properties_t *src_get_properties(void *data)
{
	UNUSED_PARAMETER(data);
	obs_properties_t *props = obs_properties_create();
	obs_property_t *list = obs_properties_add_list(props, S_CAMERA, "Camera", OBS_COMBO_TYPE_LIST,
						       OBS_COMBO_FORMAT_STRING);
	obs_property_list_add_string(list, "Auto (first available)", "");
	for (int n = 0; n < BMCAM_MAX_SLOTS; n++) {
		map_handle_t m = 0;
		uint8_t *p = map_slot(n, &m);
		if (!p)
			continue;
		struct cineremote_header *h = (struct cineremote_header *)p;
		if (header_live(h) && h->cam_id[0]) {
			char label[160], id[64];
			snprintf(label, sizeof(label), "%.127s", h->name[0] ? h->name : h->cam_id);
			snprintf(id, sizeof(id), "%.63s", h->cam_id);
			obs_property_list_add_string(list, label, id);
		}
		unmap_slot(p, m);
	}
	obs_properties_add_bool(props, S_AUDIO, "Receive audio from this camera");
	obs_properties_add_bool(props, S_BUFFERED, "Buffer frames (smoother on bad Wi-Fi, adds delay)");
	obs_properties_add_button2(props, "refresh", "Refresh camera list", refresh_clicked, NULL);
	return props;
}

static void src_defaults(obs_data_t *settings)
{
	obs_data_set_default_string(settings, S_CAMERA, "");
	obs_data_set_default_bool(settings, S_AUDIO, true);
	obs_data_set_default_bool(settings, S_BUFFERED, false);
}

static struct obs_source_info cineremote_source_info = {
	.id = "cineremote_camera",
	.type = OBS_SOURCE_TYPE_INPUT,
	.output_flags = OBS_SOURCE_ASYNC_VIDEO | OBS_SOURCE_AUDIO | OBS_SOURCE_DO_NOT_DUPLICATE,
	.get_name = src_get_name,
	.create = src_create,
	.destroy = src_destroy,
	.update = src_update,
	.get_defaults = src_defaults,
	.get_properties = src_get_properties,
	.icon_type = OBS_ICON_TYPE_CAMERA,
};

bool obs_module_load(void)
{
	obs_register_source(&cineremote_source_info);
	blog(LOG_INFO, "CineRemote Camera plugin v%s loaded", PLUGIN_VERSION);
	return true;
}
