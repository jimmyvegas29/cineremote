/* Standalone test of the shared-memory contract the OBS plugin relies on.
 * Uses the plugin's own cineremote-shm.h and the same pointer math as plugin-main.c's emit_video/emit_audio.
 * Builds with just windows.h — no libobs. Run while headless_publish.py is publishing. */
#include <windows.h>
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include "../src/cineremote-shm.h"

static uint8_t *map_slot(int n, HANDLE *out_map)
{
	char a[64];
	wchar_t w[64];
	snprintf(a, sizeof(a), "Local\\CineRemote_v1_slot%d", n);
	MultiByteToWideChar(CP_UTF8, 0, a, -1, w, 64);
	HANDLE m = OpenFileMappingW(FILE_MAP_READ, FALSE, w);
	if (!m)
		return NULL;
	uint8_t *p = (uint8_t *)MapViewOfFile(m, FILE_MAP_READ, 0, 0, 0);
	if (!p) { CloseHandle(m); return NULL; }
	struct cineremote_header *h = (struct cineremote_header *)p;
	if (h->magic != BMCAM_MAGIC || h->version != BMCAM_VERSION) {
		UnmapViewOfFile(p); CloseHandle(m); return NULL;
	}
	*out_map = m;
	return p;
}

int main(void)
{
	int slot = -1;
	HANDLE map = NULL;
	uint8_t *base = NULL;
	struct cineremote_header *h = NULL;
	for (int n = 0; n < BMCAM_MAX_SLOTS; n++) {
		base = map_slot(n, &map);
		if (base) { h = (struct cineremote_header *)base; if (h->active) { slot = n; break; }
			    UnmapViewOfFile(base); CloseHandle(map); base = NULL; }
	}
	if (!base) { printf("FAIL: no active slot found\n"); return 2; }
	printf("slot %d: name='%s' cam_id='%s' audio %uHz x%u ring=%u\n", slot, h->name, h->cam_id,
	       h->audio_rate, h->audio_channels, h->audio_ring_frames);

	char evta[80]; wchar_t evtw[80];
	snprintf(evta, sizeof(evta), "Local\\CineRemote_v1_slot%d_evt", slot);
	MultiByteToWideChar(CP_UTF8, 0, evta, -1, evtw, 80);
	HANDLE evt = OpenEventW(SYNCHRONIZE, FALSE, evtw);

	uint64_t last_vseq = 0, last_apos = h->audio_write_frames, first_apos = h->audio_write_frames;
	int vframes = 0, good = 0, nonzero = 0;
	uint32_t lw = 0, lh = 0;
	ULONGLONG t0 = GetTickCount64();
	while (GetTickCount64() - t0 < 6000) {
		if (evt) WaitForSingleObject(evt, 200); else Sleep(16);
		if (!h->active) { printf("slot went inactive\n"); break; }
		uint64_t seq = h->video_seq;
		if (seq != last_vseq) {
			last_vseq = seq;
			uint32_t idx = h->video_index;
			if (idx >= 3) continue;
			struct cineremote_vframe vf = h->vf[idx];
			vframes++;
			uint8_t *vb = base + BMCAM_HEADER_SIZE + BMCAM_AUDIO_BYTES + (uint64_t)idx * BMCAM_VIDEO_BUF_SIZE;
			uint32_t ch = (vf.height + 1) / 2;
			uint8_t *yp = vb;
			uint8_t *up = vb + (size_t)vf.stride_y * vf.height;
			uint8_t *vp = up + (size_t)vf.stride_u * ch;
			/* validate planes are inside the buffer */
			size_t used = (size_t)(vp - vb) + (size_t)vf.stride_v * ch;
			int ok = vf.format == 1 && vf.width && vf.height &&
			         used <= BMCAM_VIDEO_BUF_SIZE && vf.data_size <= BMCAM_VIDEO_BUF_SIZE;
			/* checksum a few pixels to confirm non-zero image data */
			uint64_t sum = 0;
			for (int i = 0; i < 4096 && i < (int)(vf.stride_y * vf.height); i += 7) sum += yp[i];
			if (ok) good++;
			if (sum) nonzero++;
			if (vframes <= 2 || (vf.width != lw || vf.height != lh)) {
				printf("  frame seq=%llu idx=%u %ux%u strides=%u/%u/%u planes_ok=%d ylumsum=%llu\n",
				       (unsigned long long)seq, idx, vf.width, vf.height,
				       vf.stride_y, vf.stride_u, vf.stride_v, ok, (unsigned long long)sum);
				lw = vf.width; lh = vf.height;
			}
			(void)up; (void)vp;
		}
		uint64_t w = h->audio_write_frames;
		if (w > last_apos) last_apos = w;
	}
	printf("RESULT: video_frames=%d geometry_ok=%d nonzero_image=%d audio_frames_advanced=%llu\n",
	       vframes, good, nonzero, (unsigned long long)(last_apos - first_apos));
	int pass = vframes > 30 && good == vframes && nonzero > 0 && (last_apos - first_apos) > 10000;
	printf("%s\n", pass ? "PASS" : "FAIL");
	if (evt) CloseHandle(evt);
	UnmapViewOfFile(base); CloseHandle(map);
	return pass ? 0 : 1;
}
