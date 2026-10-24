// CineRemote - Windows 11 virtual webcam media source.
//
// A COM in-proc server registered under HKLM. The app calls MFCreateVirtualCamera() with one of our
// CLSIDs; the Windows Camera Frame Server then loads this DLL, activates the source (Activator ->
// MediaSource -> MediaStream) and pulls NV12 1920x1080 samples. Frames come from the
// CineRemote app through a Global\ shared-memory section (vcam_shared.h).
//
// Threading rules: the stream lock only guards state/queue/token bookkeeping. Frame copies, sample
// allocation and logging always happen outside it, so a slow consumer can't stall Frame Server calls.

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <unknwn.h>
#include <initguid.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mferror.h>
#include <ks.h>
#include <ksmedia.h>
#include <ksproxy.h>
#include <sddl.h>
#include <wrl/client.h>
#include <atomic>
#include <deque>
#include <mutex>
#include <new>
#include <cstdio>
#include <cstring>
#include "vcam_shared.h"

using Microsoft::WRL::ComPtr;

// {28F54685-06FD-11D2-B27A-00A0C9223196}
static const IID IID_IKsControl_ = {0x28f54685, 0x06fd, 0x11d2, {0xb2, 0x7a, 0x00, 0xa0, 0xc9, 0x22, 0x31, 0x96}};

static HMODULE g_module;
static std::atomic<long> g_objects{0};
static std::atomic<long> g_locks{0};

static const wchar_t *g_clsid_str[VCAM_SLOTS] = {VCAM_CLSID_STR0, VCAM_CLSID_STR1, VCAM_CLSID_STR2, VCAM_CLSID_STR3};

// With no app feeding frames, deliver (black) at the nominal rate. While the app IS feeding frames
// we deliver exactly its frames as they arrive, and only repeat as a keep-alive if it stalls -
// repeating on a 30 Hz grid would interleave duplicates between e.g. 24 fps phone frames (judder).
static const LONGLONG REPEAT_US = 1000000 / VCAM_FPS;
static const LONGLONG KEEPALIVE_US = 100000;

static LONGLONG now_us() // QPC-based: GetTickCount64 only ticks every 15.6 ms
{
	static LARGE_INTEGER f = [] { LARGE_INTEGER x; QueryPerformanceFrequency(&x); return x; }();
	LARGE_INTEGER c;
	QueryPerformanceCounter(&c);
	return c.QuadPart * 1000000 / f.QuadPart;
}

// ------------------------------------------------------------------------------------------ log
// Lifecycle events only (never per frame, never under a lock). Writable by LocalService only.
static void vlog(const char *fmt, ...)
{
	char buf[512];
	SYSTEMTIME st;
	GetLocalTime(&st);
	int n = snprintf(buf, sizeof(buf), "%02d:%02d:%02d.%03d [%lu] ", st.wHour, st.wMinute, st.wSecond,
			 st.wMilliseconds, GetCurrentProcessId());
	va_list ap;
	va_start(ap, fmt);
	vsnprintf(buf + n, sizeof(buf) - n - 2, fmt, ap);
	va_end(ap);
	strcat_s(buf, "\n");
	OutputDebugStringA(buf);
	HANDLE f = CreateFileW(L"C:\\ProgramData\\BMCamRemote\\vcam.log", FILE_APPEND_DATA, FILE_SHARE_READ, nullptr,
			       OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL | FILE_FLAG_OPEN_REPARSE_POINT, nullptr);
	if (f == INVALID_HANDLE_VALUE)
		return;
	LARGE_INTEGER size;
	if (GetFileSizeEx(f, &size) && size.QuadPart > (1 << 20)) {
		CloseHandle(f); // keep it bounded: start over past 1 MB
		f = CreateFileW(L"C:\\ProgramData\\BMCamRemote\\vcam.log", GENERIC_WRITE, FILE_SHARE_READ, nullptr,
				CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL | FILE_FLAG_OPEN_REPARSE_POINT, nullptr);
		if (f == INVALID_HANDLE_VALUE)
			return;
	}
	DWORD w;
	WriteFile(f, buf, (DWORD)strlen(buf), &w, nullptr);
	CloseHandle(f);
}

// ------------------------------------------------------------------------------------------ shm
struct SharedFrames {
	HANDLE map = nullptr;
	HANDLE evt = nullptr;
	uint8_t *base = nullptr;
	vcam_header *hdr = nullptr;
	ULONGLONG next_try = 0;

	// Returns true when mapped. Retries at most once a second.
	bool open(int slot)
	{
		if (base)
			return true;
		ULONGLONG now = GetTickCount64();
		if (now < next_try)
			return false;
		next_try = now + 1000;
		wchar_t name[64];
		swprintf_s(name, VCAM_MAP_NAME, slot);
		// The app (an interactive user) may only map read/write and signal; we run as LocalService.
		PSECURITY_DESCRIPTOR sd = nullptr;
		ConvertStringSecurityDescriptorToSecurityDescriptorW(L"D:P(A;;GA;;;SY)(A;;GA;;;LS)(A;;0x6;;;IU)",
								     SDDL_REVISION_1, &sd, nullptr);
		SECURITY_ATTRIBUTES sa = {sizeof(sa), sd, FALSE};
		map = CreateFileMappingW(INVALID_HANDLE_VALUE, sd ? &sa : nullptr, PAGE_READWRITE, 0, VCAM_MAP_SIZE, name);
		DWORD err = GetLastError();
		if (sd)
			LocalFree(sd);
		if (!map)
			map = OpenFileMappingW(FILE_MAP_READ | FILE_MAP_WRITE, FALSE, name);
		if (!map)
			return false;
		base = (uint8_t *)MapViewOfFile(map, FILE_MAP_READ | FILE_MAP_WRITE, 0, 0, VCAM_MAP_SIZE);
		if (!base) {
			CloseHandle(map);
			map = nullptr;
			return false;
		}
		hdr = (vcam_header *)base;
		if (hdr->magic != VCAM_MAGIC) {
			hdr->width = VCAM_WIDTH;
			hdr->height = VCAM_HEIGHT;
			hdr->version = VCAM_VERSION;
			hdr->buf_index = 0;
			hdr->reader_index = VCAM_NO_BUFFER;
			hdr->magic = VCAM_MAGIC;
		}
		swprintf_s(name, VCAM_EVT_NAME, slot);
		sd = nullptr;
		ConvertStringSecurityDescriptorToSecurityDescriptorW(L"D:P(A;;GA;;;SY)(A;;GA;;;LS)(A;;0x100002;;;IU)",
								     SDDL_REVISION_1, &sd, nullptr);
		sa.lpSecurityDescriptor = sd;
		evt = CreateEventW(sd ? &sa : nullptr, FALSE, FALSE, name);
		if (sd)
			LocalFree(sd);
		vlog("slot %d: shared memory ready (%s, event %s)", slot, err == ERROR_ALREADY_EXISTS ? "existing" : "created",
		     evt ? "ok" : "none");
		return true;
	}

	void close()
	{
		if (base)
			UnmapViewOfFile(base);
		if (map)
			CloseHandle(map);
		if (evt)
			CloseHandle(evt);
		base = nullptr;
		hdr = nullptr;
		map = nullptr;
		evt = nullptr;
	}
};

// ------------------------------------------------------------------------------------------ helpers
static HRESULT make_media_type(IMFMediaType **out)
{
	ComPtr<IMFMediaType> t;
	HRESULT hr = MFCreateMediaType(&t);
	if (FAILED(hr))
		return hr;
	t->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
	t->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12);
	MFSetAttributeSize(t.Get(), MF_MT_FRAME_SIZE, VCAM_WIDTH, VCAM_HEIGHT);
	MFSetAttributeRatio(t.Get(), MF_MT_FRAME_RATE, VCAM_FPS, 1);
	MFSetAttributeRatio(t.Get(), MF_MT_FRAME_RATE_RANGE_MAX, VCAM_FPS, 1);
	MFSetAttributeRatio(t.Get(), MF_MT_FRAME_RATE_RANGE_MIN, VCAM_FPS, 1);
	MFSetAttributeRatio(t.Get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
	t->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
	t->SetUINT32(MF_MT_ALL_SAMPLES_INDEPENDENT, TRUE);
	t->SetUINT32(MF_MT_FIXED_SIZE_SAMPLES, TRUE);
	t->SetUINT32(MF_MT_DEFAULT_STRIDE, VCAM_WIDTH);
	t->SetUINT32(MF_MT_SAMPLE_SIZE, VCAM_FRAME_SIZE);
	t->SetUINT32(MF_MT_AVG_BITRATE, VCAM_FRAME_SIZE * 8 * VCAM_FPS);
	t->SetUINT32(MF_MT_VIDEO_NOMINAL_RANGE, MFNominalRange_16_235);
	t->SetUINT32(MF_MT_YUV_MATRIX, MFVideoTransferMatrix_BT709);
	*out = t.Detach();
	return S_OK;
}

// Copy (or synthesize black) NV12 into a buffer. 2D buffers (and D3D11 NV12 surfaces) place the
// chroma plane at pitch * height; contiguous buffers at width * height. Both planes are bounds-checked.
static void fill_nv12(BYTE *dst, LONG pitch, DWORD len, const uint8_t *src)
{
	const UINT w = VCAM_WIDTH, h = VCAM_HEIGHT;
	if (pitch < (LONG)w)
		return;
	const size_t p = (size_t)pitch;
	const size_t uv_off = p * h;
	for (UINT y = 0; y < h && p * y + w <= len; y++) {
		if (src)
			memcpy(dst + p * y, src + (size_t)w * y, w);
		else
			memset(dst + p * y, 16, w);
	}
	for (UINT y = 0; y < h / 2 && uv_off + p * y + w <= len; y++) {
		if (src)
			memcpy(dst + uv_off + p * y, src + (size_t)w * h + (size_t)w * y, w);
		else
			memset(dst + uv_off + p * y, 128, w);
	}
}

// ------------------------------------------------------------------------------------------ stream
class MediaSource;

class MediaStream : public IMFMediaStream2, public IKsControl {
public:
	MediaStream(MediaSource *source, int slot);
	~MediaStream() { g_objects--; }

	HRESULT Initialize(IMFStreamDescriptor *sd)
	{
		HRESULT hr = MFCreateEventQueue(&_queue);
		if (FAILED(hr))
			return hr;
		_sd = sd;
		_stop_event = CreateEventW(nullptr, TRUE, FALSE, nullptr);
		_timer = CreateWaitableTimerExW(nullptr, nullptr, CREATE_WAITABLE_TIMER_HIGH_RESOLUTION, TIMER_ALL_ACCESS);
		if (!_timer)
			_timer = CreateWaitableTimerW(nullptr, FALSE, nullptr);
		if (!_stop_event || !_timer)
			return HRESULT_FROM_WIN32(GetLastError());
		_thread = CreateThread(nullptr, 0, &MediaStream::ThreadProc, this, 0, nullptr);
		return _thread ? S_OK : HRESULT_FROM_WIN32(GetLastError());
	}

	// IUnknown
	STDMETHODIMP QueryInterface(REFIID riid, void **ppv) override
	{
		if (!ppv)
			return E_POINTER;
		if (riid == IID_IUnknown || riid == __uuidof(IMFMediaEventGenerator) || riid == __uuidof(IMFMediaStream) ||
		    riid == __uuidof(IMFMediaStream2))
			*ppv = static_cast<IMFMediaStream2 *>(this);
		else if (riid == IID_IKsControl_)
			*ppv = static_cast<IKsControl *>(this);
		else {
			*ppv = nullptr;
			return E_NOINTERFACE;
		}
		AddRef();
		return S_OK;
	}
	STDMETHODIMP_(ULONG) AddRef() override { return ++_ref; }
	STDMETHODIMP_(ULONG) Release() override
	{
		long r = --_ref;
		if (r == 0)
			delete this;
		return r;
	}

	// IMFMediaEventGenerator
	STDMETHODIMP GetEvent(DWORD flags, IMFMediaEvent **e) override
	{
		ComPtr<IMFMediaEventQueue> q;
		{
			std::lock_guard<std::mutex> l(_lock);
			if (!_queue)
				return MF_E_SHUTDOWN;
			q = _queue;
		}
		return q->GetEvent(flags, e);
	}
	STDMETHODIMP BeginGetEvent(IMFAsyncCallback *cb, IUnknown *st) override
	{
		ComPtr<IMFMediaEventQueue> q = Queue();
		return q ? q->BeginGetEvent(cb, st) : MF_E_SHUTDOWN;
	}
	STDMETHODIMP EndGetEvent(IMFAsyncResult *r, IMFMediaEvent **e) override
	{
		ComPtr<IMFMediaEventQueue> q = Queue();
		return q ? q->EndGetEvent(r, e) : MF_E_SHUTDOWN;
	}
	STDMETHODIMP QueueEvent(MediaEventType t, REFGUID g, HRESULT s, const PROPVARIANT *v) override
	{
		ComPtr<IMFMediaEventQueue> q = Queue();
		return q ? q->QueueEventParamVar(t, g, s, v) : MF_E_SHUTDOWN;
	}

	// IMFMediaStream
	STDMETHODIMP GetMediaSource(IMFMediaSource **ms) override;
	STDMETHODIMP GetStreamDescriptor(IMFStreamDescriptor **sd) override
	{
		if (!sd)
			return E_POINTER;
		std::lock_guard<std::mutex> l(_lock);
		if (!_sd)
			return MF_E_SHUTDOWN;
		*sd = _sd.Get();
		(*sd)->AddRef();
		return S_OK;
	}
	STDMETHODIMP RequestSample(IUnknown *token) override
	{
		{
			std::lock_guard<std::mutex> l(_lock);
			if (!_queue)
				return MF_E_SHUTDOWN;
			if (_state != MF_STREAM_STATE_RUNNING)
				return MF_E_MEDIA_SOURCE_WRONGSTATE;
			if (token)
				token->AddRef();
			_pending.push_back(token);
		}
		SetEvent(_wake);
		return S_OK;
	}

	// IMFMediaStream2
	STDMETHODIMP SetStreamState(MF_STREAM_STATE s) override
	{
		{
			std::lock_guard<std::mutex> l(_lock);
			if (!_queue)
				return MF_E_SHUTDOWN;
			_state = s;
			if (s != MF_STREAM_STATE_RUNNING)
				ClearPendingLocked();
		}
		vlog("slot %d: SetStreamState %d", _slot, (int)s);
		SetEvent(_wake);
		return S_OK;
	}
	STDMETHODIMP GetStreamState(MF_STREAM_STATE *s) override
	{
		if (!s)
			return E_POINTER;
		std::lock_guard<std::mutex> l(_lock);
		*s = _state;
		return S_OK;
	}

	// IKsControl
	STDMETHODIMP KsProperty(PKSPROPERTY, ULONG, LPVOID, ULONG, ULONG *) override
	{
		return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND);
	}
	STDMETHODIMP KsMethod(PKSMETHOD, ULONG, LPVOID, ULONG, ULONG *) override
	{
		return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND);
	}
	STDMETHODIMP KsEvent(PKSEVENT, ULONG, LPVOID, ULONG, ULONG *) override
	{
		return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND);
	}

	// called by MediaSource (outside the source's own lock)
	HRESULT Start(const PROPVARIANT *start)
	{
		ComPtr<IMFVideoSampleAllocatorEx> alloc;
		bool init_alloc = false;
		ComPtr<IMFMediaEventQueue> q;
		{
			std::lock_guard<std::mutex> l(_lock);
			if (!_queue)
				return MF_E_SHUTDOWN;
			_state = MF_STREAM_STATE_RUNNING;
			if (_allocator && !_allocator_ready) {
				alloc = _allocator;
				init_alloc = true;
			}
			q = _queue;
		}
		if (init_alloc) {
			ComPtr<IMFMediaTypeHandler> h;
			ComPtr<IMFMediaType> t;
			HRESULT hr = E_FAIL;
			if (SUCCEEDED(_sd->GetMediaTypeHandler(&h)) && SUCCEEDED(h->GetCurrentMediaType(&t)))
				hr = alloc->InitializeSampleAllocator(10, t.Get());
			{
				std::lock_guard<std::mutex> l(_lock);
				_allocator_ready = SUCCEEDED(hr) && _allocator.Get() == alloc.Get();
			}
			vlog("slot %d: InitializeSampleAllocator 0x%08lx", _slot, hr);
		}
		vlog("slot %d: stream started", _slot);
		SetEvent(_wake);
		return q->QueueEventParamVar(MEStreamStarted, GUID_NULL, S_OK, start);
	}
	HRESULT Stop()
	{
		ComPtr<IMFMediaEventQueue> q;
		{
			std::lock_guard<std::mutex> l(_lock);
			if (!_queue)
				return MF_E_SHUTDOWN;
			_state = MF_STREAM_STATE_STOPPED;
			ClearPendingLocked();
			q = _queue;
		}
		return q->QueueEventParamVar(MEStreamStopped, GUID_NULL, S_OK, nullptr);
	}
	void SetAllocator(IUnknown *alloc)
	{
		ComPtr<IMFVideoSampleAllocatorEx> a;
		if (alloc)
			alloc->QueryInterface(IID_PPV_ARGS(&a));
		{
			std::lock_guard<std::mutex> l(_lock);
			_allocator = a;
			_allocator_ready = false;
		}
		vlog("slot %d: allocator %s", _slot, a ? "provided" : "none");
	}
	void Shutdown()
	{
		if (_stop_event)
			SetEvent(_stop_event);
		if (_thread) {
			// The worker never blocks for long (no locks held across I/O), so wait for it fully:
			// returning early would let it run on freed memory.
			WaitForSingleObject(_thread, INFINITE);
			CloseHandle(_thread);
			_thread = nullptr;
		}
		MediaSource *src;
		ComPtr<IMFVideoSampleAllocatorEx> alloc;
		{
			std::lock_guard<std::mutex> l(_lock);
			ClearPendingLocked();
			if (_queue) {
				_queue->Shutdown();
				_queue.Reset();
			}
			alloc.Swap(_allocator);
			_sd.Reset();
			src = _source;
			_source = nullptr;
		}
		if (alloc)
			alloc->UninitializeSampleAllocator();
		_shm.close();
		for (HANDLE *h : {&_stop_event, &_timer, &_wake}) {
			if (*h) {
				CloseHandle(*h);
				*h = nullptr;
			}
		}
		ReleaseSource(src);
	}

private:
	static void ReleaseSource(MediaSource *s);

	ComPtr<IMFMediaEventQueue> Queue()
	{
		std::lock_guard<std::mutex> l(_lock);
		return _queue;
	}

	void ClearPendingLocked()
	{
		for (IUnknown *t : _pending)
			if (t)
				t->Release();
		_pending.clear();
	}

	// Build one sample from the newest frame (or black). Runs on the worker thread, no lock held.
	HRESULT MakeSample(IMFVideoSampleAllocatorEx *alloc, IMFSample **out)
	{
		ComPtr<IMFSample> sample;
		HRESULT hr = alloc ? alloc->AllocateSample(&sample) : E_FAIL;
		bool own_buffer = FAILED(hr);
		if (own_buffer) {
			ComPtr<IMFMediaBuffer> buf;
			hr = MFCreate2DMediaBuffer(VCAM_WIDTH, VCAM_HEIGHT, MFVideoFormat_NV12.Data1, FALSE, &buf);
			if (SUCCEEDED(hr))
				hr = MFCreateSample(&sample);
			if (SUCCEEDED(hr))
				hr = sample->AddBuffer(buf.Get());
			if (FAILED(hr))
				return hr;
		}
		ComPtr<IMFMediaBuffer> buf;
		hr = sample->GetBufferByIndex(0, &buf);
		if (FAILED(hr))
			return hr;

		// Newest frame from the app, or black when it has gone quiet. Mark the buffer as being read
		// so the writer picks a different one (see vcam_shared.h).
		const uint8_t *src = nullptr;
		vcam_header *hdr = _shm.hdr;
		if (hdr) {
			ULONGLONG now = GetTickCount64();
			hdr->reader_tick = now;
			if (hdr->seq && hdr->writer_tick && now - hdr->writer_tick < 2000) {
				uint32_t idx = hdr->buf_index % VCAM_BUFFERS;
				hdr->reader_index = idx;
				MemoryBarrier();
				uint32_t again = hdr->buf_index % VCAM_BUFFERS;
				if (again != idx) {
					idx = again;
					hdr->reader_index = idx;
					MemoryBarrier();
				}
				src = _shm.base + VCAM_HEADER_SIZE + (size_t)idx * VCAM_FRAME_SIZE;
				_last_seq = hdr->seq;
			}
		}

		ComPtr<IMF2DBuffer2> b2;
		BYTE *scan0 = nullptr, *start = nullptr;
		LONG pitch = 0;
		DWORD len = 0;
		if (SUCCEEDED(buf.As(&b2)) &&
		    SUCCEEDED(b2->Lock2DSize(MF2DBuffer_LockFlags_Write, &scan0, &pitch, &start, &len))) {
			fill_nv12(scan0, pitch, (DWORD)(len - (scan0 - start)), src);
			b2->Unlock2D();
		} else {
			BYTE *p = nullptr;
			DWORD maxlen = 0;
			hr = buf->Lock(&p, &maxlen, nullptr);
			if (SUCCEEDED(hr)) {
				fill_nv12(p, VCAM_WIDTH, maxlen, src);
				buf->Unlock();
			}
		}
		if (hdr)
			hdr->reader_index = VCAM_NO_BUFFER;
		if (FAILED(hr))
			return hr;
		buf->SetCurrentLength(VCAM_FRAME_SIZE);
		sample->SetSampleTime(MFGetSystemTime());
		sample->SetSampleDuration(10000000 / VCAM_FPS);
		*out = sample.Detach();
		return S_OK;
	}

	static DWORD WINAPI ThreadProc(LPVOID p)
	{
		static_cast<MediaStream *>(p)->Run();
		return 0;
	}

	// Deliver a sample as soon as the app publishes a new frame (frame event), or repeat the last
	// one every REPEAT_MS so the client keeps flowing. Never more than one sample per request.
	void Run()
	{
		LONGLONG last_delivery = 0;
		uint64_t delivered = 0;
		for (;;) {
			bool running;
			{
				std::lock_guard<std::mutex> l(_lock);
				running = _state == MF_STREAM_STATE_RUNNING && _queue;
			}
			if (running && !_shm.base)
				_shm.open(_slot);
			HANDLE waits[3] = {_stop_event, _wake, _shm.evt};
			DWORD nwait = _shm.evt ? 3 : 2;
			bool live = _shm.hdr && _shm.hdr->writer_tick && GetTickCount64() - _shm.hdr->writer_tick < 500;
			LONGLONG repeat_us = live ? KEEPALIVE_US : REPEAT_US;
			LONGLONG timeout_us = running ? repeat_us : 250000;
			if (running && last_delivery) {
				LONGLONG since = now_us() - last_delivery;
				timeout_us = since >= repeat_us ? 0 : repeat_us - since;
			}
			if (!_shm.evt && running && timeout_us > 10000)
				timeout_us = 10000; // no frame event yet: poll the sequence number
			LARGE_INTEGER due;
			due.QuadPart = -timeout_us * 10; // relative, 100 ns units
			SetWaitableTimer(_timer, &due, 0, nullptr, nullptr, FALSE);
			HANDLE all[4] = {waits[0], waits[1], waits[2], _timer};
			if (nwait == 2)
				all[2] = _timer;
			DWORD r = WaitForMultipleObjects(nwait + 1, all, FALSE, INFINITE);
			if (r == WAIT_OBJECT_0)
				break;

			// Decide whether to deliver, and take a request token, under the lock.
			IUnknown *token = nullptr;
			ComPtr<IMFVideoSampleAllocatorEx> alloc;
			{
				std::lock_guard<std::mutex> l(_lock);
				if (!_queue || _state != MF_STREAM_STATE_RUNNING || _pending.empty())
					continue;
				bool new_frame = _shm.hdr && _shm.hdr->seq != _last_seq;
				bool due_now = now_us() - last_delivery >= repeat_us - 1000;
				if (!new_frame && !due_now)
					continue;
				token = _pending.front();
				_pending.pop_front();
				if (_allocator_ready)
					alloc = _allocator;
			}
			ComPtr<IMFSample> sample;
			HRESULT hr = MakeSample(alloc.Get(), &sample);
			if (SUCCEEDED(hr)) {
				if (token)
					sample->SetUnknown(MFSampleExtension_Token, token);
				else
					sample->DeleteItem(MFSampleExtension_Token); // recycled samples may carry an old one
				ComPtr<IMFMediaEventQueue> q = Queue();
				hr = q ? q->QueueEventParamUnk(MEMediaSample, GUID_NULL, S_OK, sample.Get()) : MF_E_SHUTDOWN;
			}
			if (token)
				token->Release();
			last_delivery = now_us();
			if (FAILED(hr) && hr != MF_E_SHUTDOWN)
				vlog("slot %d: deliver failed 0x%08lx", _slot, hr);
			else if (++delivered == 1)
				vlog("slot %d: first sample delivered", _slot);
		}
	}

	std::atomic<long> _ref{1};
	std::mutex _lock;
	MediaSource *_source; // strong reference, released in Shutdown
	int _slot;
	ComPtr<IMFMediaEventQueue> _queue;
	ComPtr<IMFStreamDescriptor> _sd;
	ComPtr<IMFVideoSampleAllocatorEx> _allocator;
	bool _allocator_ready = false;
	MF_STREAM_STATE _state = MF_STREAM_STATE_STOPPED;
	std::deque<IUnknown *> _pending;
	HANDLE _thread = nullptr;
	HANDLE _stop_event = nullptr;
	HANDLE _timer = nullptr;
	HANDLE _wake = CreateEventW(nullptr, FALSE, FALSE, nullptr);
	SharedFrames _shm;       // worker thread only (and Shutdown after the worker exits)
	uint64_t _last_seq = 0;  // worker thread only
};

// ------------------------------------------------------------------------------------------ source
class MediaSource : public IMFMediaSourceEx, public IMFGetService, public IKsControl, public IMFSampleAllocatorControl {
public:
	explicit MediaSource(int slot) : _slot(slot) { g_objects++; }
	~MediaSource()
	{
		if (_queue)
			Shutdown(); // released without Shutdown: stop the stream thread instead of leaking it
		g_objects--;
	}

	HRESULT Initialize()
	{
		HRESULT hr = MFCreateEventQueue(&_queue);
		if (FAILED(hr))
			return hr;
		hr = MFCreateAttributes(&_attrs, 4);
		if (FAILED(hr))
			return hr;

		ComPtr<IMFMediaType> type;
		hr = make_media_type(&type);
		if (FAILED(hr))
			return hr;
		IMFMediaType *types[] = {type.Get()};
		ComPtr<IMFStreamDescriptor> sd;
		hr = MFCreateStreamDescriptor(0, 1, types, &sd);
		if (FAILED(hr))
			return hr;
		ComPtr<IMFMediaTypeHandler> handler;
		sd->GetMediaTypeHandler(&handler);
		handler->SetCurrentMediaType(type.Get());
		// Stream attributes the Frame Server looks for on a camera stream.
		sd->SetGUID(MF_DEVICESTREAM_STREAM_CATEGORY, PINNAME_VIDEO_CAPTURE);
		sd->SetUINT32(MF_DEVICESTREAM_STREAM_ID, 0);
		sd->SetUINT32(MF_DEVICESTREAM_FRAMESERVER_SHARED, 1);
		sd->SetUINT32(MF_DEVICESTREAM_ATTRIBUTE_FRAMESOURCE_TYPES, MFFrameSourceTypes_Color);

		IMFStreamDescriptor *sds[] = {sd.Get()};
		hr = MFCreatePresentationDescriptor(1, sds, &_pd);
		if (FAILED(hr))
			return hr;
		_pd->SelectStream(0);

		_stream = new (std::nothrow) MediaStream(this, _slot);
		if (!_stream)
			return E_OUTOFMEMORY;
		return _stream->Initialize(sd.Get());
	}

	// IUnknown
	STDMETHODIMP QueryInterface(REFIID riid, void **ppv) override
	{
		if (!ppv)
			return E_POINTER;
		if (riid == IID_IUnknown || riid == __uuidof(IMFMediaEventGenerator) || riid == __uuidof(IMFMediaSource) ||
		    riid == __uuidof(IMFMediaSourceEx))
			*ppv = static_cast<IMFMediaSourceEx *>(this);
		else if (riid == __uuidof(IMFGetService))
			*ppv = static_cast<IMFGetService *>(this);
		else if (riid == IID_IKsControl_)
			*ppv = static_cast<IKsControl *>(this);
		else if (riid == __uuidof(IMFSampleAllocatorControl))
			*ppv = static_cast<IMFSampleAllocatorControl *>(this);
		else {
			*ppv = nullptr;
			return E_NOINTERFACE;
		}
		AddRef();
		return S_OK;
	}
	STDMETHODIMP_(ULONG) AddRef() override { return ++_ref; }
	STDMETHODIMP_(ULONG) Release() override
	{
		long r = --_ref;
		if (r == 0)
			delete this;
		return r;
	}

	// IMFMediaEventGenerator
	STDMETHODIMP GetEvent(DWORD flags, IMFMediaEvent **e) override
	{
		ComPtr<IMFMediaEventQueue> q = Queue();
		return q ? q->GetEvent(flags, e) : MF_E_SHUTDOWN;
	}
	STDMETHODIMP BeginGetEvent(IMFAsyncCallback *cb, IUnknown *st) override
	{
		ComPtr<IMFMediaEventQueue> q = Queue();
		return q ? q->BeginGetEvent(cb, st) : MF_E_SHUTDOWN;
	}
	STDMETHODIMP EndGetEvent(IMFAsyncResult *r, IMFMediaEvent **e) override
	{
		ComPtr<IMFMediaEventQueue> q = Queue();
		return q ? q->EndGetEvent(r, e) : MF_E_SHUTDOWN;
	}
	STDMETHODIMP QueueEvent(MediaEventType t, REFGUID g, HRESULT s, const PROPVARIANT *v) override
	{
		ComPtr<IMFMediaEventQueue> q = Queue();
		return q ? q->QueueEventParamVar(t, g, s, v) : MF_E_SHUTDOWN;
	}

	// IMFMediaSource
	STDMETHODIMP GetCharacteristics(DWORD *c) override
	{
		if (!c)
			return E_POINTER;
		if (!Queue())
			return MF_E_SHUTDOWN;
		*c = MFMEDIASOURCE_IS_LIVE;
		return S_OK;
	}
	STDMETHODIMP CreatePresentationDescriptor(IMFPresentationDescriptor **pd) override
	{
		if (!pd)
			return E_POINTER;
		std::lock_guard<std::mutex> l(_lock);
		if (!_pd)
			return MF_E_SHUTDOWN;
		return _pd->Clone(pd);
	}
	STDMETHODIMP Start(IMFPresentationDescriptor *pd, const GUID *fmt, const PROPVARIANT *pos) override
	{
		if (!pd)
			return E_INVALIDARG;
		if (fmt && *fmt != GUID_NULL)
			return MF_E_UNSUPPORTED_TIME_FORMAT;
		ComPtr<IMFMediaEventQueue> q;
		MediaStream *stream;
		bool started_before;
		{
			std::lock_guard<std::mutex> l(_lock);
			if (!_queue)
				return MF_E_SHUTDOWN;
			q = _queue;
			stream = _stream;
			stream->AddRef();
			started_before = _started_once;
			_started_once = true;
		}
		vlog("slot %d: Start", _slot);
		HRESULT hr = q->QueueEventParamUnk(started_before ? MEUpdatedStream : MENewStream, GUID_NULL, S_OK,
						   static_cast<IMFMediaStream2 *>(stream));
		PROPVARIANT t;
		PropVariantInit(&t);
		t.vt = VT_I8;
		t.hVal.QuadPart = MFGetSystemTime();
		const PROPVARIANT *p = (pos && pos->vt != VT_EMPTY) ? pos : &t;
		if (SUCCEEDED(hr))
			hr = stream->Start(p);
		if (SUCCEEDED(hr))
			hr = q->QueueEventParamVar(MESourceStarted, GUID_NULL, S_OK, p);
		stream->Release();
		return hr;
	}
	STDMETHODIMP Stop() override
	{
		ComPtr<IMFMediaEventQueue> q;
		MediaStream *stream;
		{
			std::lock_guard<std::mutex> l(_lock);
			if (!_queue)
				return MF_E_SHUTDOWN;
			q = _queue;
			stream = _stream;
			stream->AddRef();
		}
		vlog("slot %d: Stop", _slot);
		stream->Stop();
		stream->Release();
		return q->QueueEventParamVar(MESourceStopped, GUID_NULL, S_OK, nullptr);
	}
	STDMETHODIMP Pause() override { return MF_E_INVALID_STATE_TRANSITION; }
	STDMETHODIMP Shutdown() override
	{
		MediaStream *stream = nullptr;
		ComPtr<IMFMediaEventQueue> q;
		{
			std::lock_guard<std::mutex> l(_lock);
			if (!_queue)
				return MF_E_SHUTDOWN;
			q.Swap(_queue);
			_pd.Reset();
			stream = _stream;
			_stream = nullptr;
		}
		vlog("slot %d: Shutdown", _slot);
		q->Shutdown();
		if (stream) {
			stream->Shutdown();
			stream->Release();
		}
		return S_OK;
	}

	// IMFMediaSourceEx
	STDMETHODIMP GetSourceAttributes(IMFAttributes **a) override
	{
		if (!a)
			return E_POINTER;
		*a = _attrs.Get();
		(*a)->AddRef();
		return S_OK;
	}
	STDMETHODIMP GetStreamAttributes(DWORD id, IMFAttributes **a) override
	{
		if (!a)
			return E_POINTER;
		if (id != 0)
			return MF_E_INVALIDSTREAMNUMBER;
		ComPtr<IMFPresentationDescriptor> pd;
		{
			std::lock_guard<std::mutex> l(_lock);
			pd = _pd;
		}
		if (!pd)
			return MF_E_SHUTDOWN;
		BOOL sel;
		ComPtr<IMFStreamDescriptor> sd;
		HRESULT hr = pd->GetStreamDescriptorByIndex(0, &sel, &sd);
		if (FAILED(hr))
			return hr;
		return sd->QueryInterface(IID_PPV_ARGS(a));
	}
	STDMETHODIMP SetD3DManager(IUnknown *) override
	{
		return S_OK; // deliberate: we deliver system-memory NV12; Frame Server handles any GPU upload
	}

	// IMFGetService
	STDMETHODIMP GetService(REFGUID, REFIID, LPVOID *) override { return MF_E_UNSUPPORTED_SERVICE; }

	// IKsControl
	STDMETHODIMP KsProperty(PKSPROPERTY, ULONG, LPVOID, ULONG, ULONG *) override
	{
		return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND);
	}
	STDMETHODIMP KsMethod(PKSMETHOD, ULONG, LPVOID, ULONG, ULONG *) override
	{
		return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND);
	}
	STDMETHODIMP KsEvent(PKSEVENT, ULONG, LPVOID, ULONG, ULONG *) override
	{
		return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND);
	}

	// IMFSampleAllocatorControl
	STDMETHODIMP SetDefaultAllocator(DWORD id, IUnknown *alloc) override
	{
		if (id != 0)
			return MF_E_INVALIDSTREAMNUMBER;
		MediaStream *stream;
		{
			std::lock_guard<std::mutex> l(_lock);
			stream = _stream;
			if (stream)
				stream->AddRef();
		}
		if (stream) {
			stream->SetAllocator(alloc);
			stream->Release();
		}
		return S_OK;
	}
	STDMETHODIMP GetAllocatorUsage(DWORD id, DWORD *in_id, MFSampleAllocatorUsage *usage) override
	{
		if (!in_id || !usage)
			return E_POINTER;
		if (id != 0)
			return MF_E_INVALIDSTREAMNUMBER;
		*in_id = id;
		*usage = MFSampleAllocatorUsage_UsesProvidedAllocator;
		return S_OK;
	}

	HRESULT GetSelf(IMFMediaSource **ms)
	{
		*ms = static_cast<IMFMediaSourceEx *>(this);
		AddRef();
		return S_OK;
	}

private:
	ComPtr<IMFMediaEventQueue> Queue()
	{
		std::lock_guard<std::mutex> l(_lock);
		return _queue;
	}

	std::atomic<long> _ref{1};
	std::mutex _lock;
	int _slot;
	ComPtr<IMFMediaEventQueue> _queue;
	ComPtr<IMFPresentationDescriptor> _pd;
	ComPtr<IMFAttributes> _attrs;
	MediaStream *_stream = nullptr;
	bool _started_once = false;
};

MediaStream::MediaStream(MediaSource *source, int slot) : _source(source), _slot(slot)
{
	g_objects++;
	// Strong reference (cleared in Shutdown) so GetMediaSource can never return a dangling pointer.
	// The cycle is broken by MediaSource::Shutdown, which the destructor also guarantees.
	if (_source)
		static_cast<IMFMediaSourceEx *>(_source)->AddRef();
}

void MediaStream::ReleaseSource(MediaSource *s)
{
	if (s)
		static_cast<IMFMediaSourceEx *>(s)->Release();
}

STDMETHODIMP MediaStream::GetMediaSource(IMFMediaSource **ms)
{
	if (!ms)
		return E_POINTER;
	MediaSource *s;
	{
		std::lock_guard<std::mutex> l(_lock);
		s = _source;
		if (s)
			static_cast<IMFMediaSourceEx *>(s)->AddRef();
	}
	if (!s)
		return MF_E_SHUTDOWN;
	HRESULT hr = s->GetSelf(ms);
	static_cast<IMFMediaSourceEx *>(s)->Release();
	return hr;
}

// ------------------------------------------------------------------------------------------ activator
// IMFActivate (an IMFAttributes store) that creates the MediaSource when the Frame Server asks.
class Activator : public IMFActivate {
public:
	explicit Activator(int slot) : _slot(slot) { g_objects++; }
	// Do NOT shut the source down here: Frame Server releases the activator but keeps using the
	// source it handed out. Shutdown is the caller's job (IMFMediaSource::Shutdown / ShutdownObject).
	~Activator() { g_objects--; }
	HRESULT Initialize() { return MFCreateAttributes(&_a, 4); }

	STDMETHODIMP QueryInterface(REFIID riid, void **ppv) override
	{
		if (!ppv)
			return E_POINTER;
		if (riid == IID_IUnknown || riid == __uuidof(IMFAttributes) || riid == __uuidof(IMFActivate)) {
			*ppv = static_cast<IMFActivate *>(this);
			AddRef();
			return S_OK;
		}
		*ppv = nullptr;
		return E_NOINTERFACE;
	}
	STDMETHODIMP_(ULONG) AddRef() override { return ++_ref; }
	STDMETHODIMP_(ULONG) Release() override
	{
		long r = --_ref;
		if (r == 0)
			delete this;
		return r;
	}

	STDMETHODIMP ActivateObject(REFIID riid, void **ppv) override
	{
		if (!ppv)
			return E_POINTER;
		*ppv = nullptr;
		std::lock_guard<std::mutex> l(_lock);
		if (!_source) {
			MediaSource *s = new (std::nothrow) MediaSource(_slot);
			if (!s)
				return E_OUTOFMEMORY;
			HRESULT hr = s->Initialize();
			if (FAILED(hr)) {
				s->Shutdown();
				s->Release();
				return hr;
			}
			_source.Attach(static_cast<IMFMediaSourceEx *>(s));
		}
		return _source->QueryInterface(riid, ppv);
	}
	STDMETHODIMP ShutdownObject() override
	{
		ComPtr<IMFMediaSourceEx> s;
		{
			std::lock_guard<std::mutex> l(_lock);
			s.Swap(_source);
		}
		if (s)
			s->Shutdown();
		return S_OK;
	}
	STDMETHODIMP DetachObject() override
	{
		std::lock_guard<std::mutex> l(_lock);
		_source.Reset();
		return S_OK;
	}

	// IMFAttributes -> forward to the internal store
	STDMETHODIMP GetItem(REFGUID k, PROPVARIANT *v) override { return _a->GetItem(k, v); }
	STDMETHODIMP GetItemType(REFGUID k, MF_ATTRIBUTE_TYPE *t) override { return _a->GetItemType(k, t); }
	STDMETHODIMP CompareItem(REFGUID k, REFPROPVARIANT v, BOOL *r) override { return _a->CompareItem(k, v, r); }
	STDMETHODIMP Compare(IMFAttributes *o, MF_ATTRIBUTES_MATCH_TYPE m, BOOL *r) override { return _a->Compare(o, m, r); }
	STDMETHODIMP GetUINT32(REFGUID k, UINT32 *v) override { return _a->GetUINT32(k, v); }
	STDMETHODIMP GetUINT64(REFGUID k, UINT64 *v) override { return _a->GetUINT64(k, v); }
	STDMETHODIMP GetDouble(REFGUID k, double *v) override { return _a->GetDouble(k, v); }
	STDMETHODIMP GetGUID(REFGUID k, GUID *v) override { return _a->GetGUID(k, v); }
	STDMETHODIMP GetStringLength(REFGUID k, UINT32 *l) override { return _a->GetStringLength(k, l); }
	STDMETHODIMP GetString(REFGUID k, LPWSTR v, UINT32 s, UINT32 *l) override { return _a->GetString(k, v, s, l); }
	STDMETHODIMP GetAllocatedString(REFGUID k, LPWSTR *v, UINT32 *l) override { return _a->GetAllocatedString(k, v, l); }
	STDMETHODIMP GetBlobSize(REFGUID k, UINT32 *s) override { return _a->GetBlobSize(k, s); }
	STDMETHODIMP GetBlob(REFGUID k, UINT8 *b, UINT32 s, UINT32 *l) override { return _a->GetBlob(k, b, s, l); }
	STDMETHODIMP GetAllocatedBlob(REFGUID k, UINT8 **b, UINT32 *s) override { return _a->GetAllocatedBlob(k, b, s); }
	STDMETHODIMP GetUnknown(REFGUID k, REFIID r, LPVOID *p) override { return _a->GetUnknown(k, r, p); }
	STDMETHODIMP SetItem(REFGUID k, REFPROPVARIANT v) override { return _a->SetItem(k, v); }
	STDMETHODIMP DeleteItem(REFGUID k) override { return _a->DeleteItem(k); }
	STDMETHODIMP DeleteAllItems() override { return _a->DeleteAllItems(); }
	STDMETHODIMP SetUINT32(REFGUID k, UINT32 v) override { return _a->SetUINT32(k, v); }
	STDMETHODIMP SetUINT64(REFGUID k, UINT64 v) override { return _a->SetUINT64(k, v); }
	STDMETHODIMP SetDouble(REFGUID k, double v) override { return _a->SetDouble(k, v); }
	STDMETHODIMP SetGUID(REFGUID k, REFGUID v) override { return _a->SetGUID(k, v); }
	STDMETHODIMP SetString(REFGUID k, LPCWSTR v) override { return _a->SetString(k, v); }
	STDMETHODIMP SetBlob(REFGUID k, const UINT8 *b, UINT32 s) override { return _a->SetBlob(k, b, s); }
	STDMETHODIMP SetUnknown(REFGUID k, IUnknown *u) override { return _a->SetUnknown(k, u); }
	STDMETHODIMP LockStore() override { return _a->LockStore(); }
	STDMETHODIMP UnlockStore() override { return _a->UnlockStore(); }
	STDMETHODIMP GetCount(UINT32 *c) override { return _a->GetCount(c); }
	STDMETHODIMP GetItemByIndex(UINT32 i, GUID *k, PROPVARIANT *v) override { return _a->GetItemByIndex(i, k, v); }
	STDMETHODIMP CopyAllItems(IMFAttributes *d) override { return _a->CopyAllItems(d); }

private:
	std::atomic<long> _ref{1};
	std::mutex _lock;
	int _slot;
	ComPtr<IMFAttributes> _a;
	ComPtr<IMFMediaSourceEx> _source;
};

// ------------------------------------------------------------------------------------------ class factory
class ClassFactory : public IClassFactory {
public:
	explicit ClassFactory(int slot) : _slot(slot) { g_objects++; }
	~ClassFactory() { g_objects--; }
	STDMETHODIMP QueryInterface(REFIID riid, void **ppv) override
	{
		if (!ppv)
			return E_POINTER;
		if (riid == IID_IUnknown || riid == IID_IClassFactory) {
			*ppv = static_cast<IClassFactory *>(this);
			AddRef();
			return S_OK;
		}
		*ppv = nullptr;
		return E_NOINTERFACE;
	}
	STDMETHODIMP_(ULONG) AddRef() override { return ++_ref; }
	STDMETHODIMP_(ULONG) Release() override
	{
		long r = --_ref;
		if (r == 0)
			delete this;
		return r;
	}
	STDMETHODIMP CreateInstance(IUnknown *outer, REFIID riid, void **ppv) override
	{
		if (outer)
			return CLASS_E_NOAGGREGATION;
		Activator *a = new (std::nothrow) Activator(_slot);
		if (!a)
			return E_OUTOFMEMORY;
		HRESULT hr = a->Initialize();
		if (SUCCEEDED(hr))
			hr = a->QueryInterface(riid, ppv);
		a->Release();
		return hr;
	}
	STDMETHODIMP LockServer(BOOL lock) override
	{
		lock ? g_locks++ : g_locks--;
		return S_OK;
	}

private:
	std::atomic<long> _ref{1};
	int _slot;
};

// ------------------------------------------------------------------------------------------ exports
static int slot_for_clsid(REFCLSID clsid)
{
	for (int i = 0; i < VCAM_SLOTS; i++) {
		CLSID c;
		if (SUCCEEDED(CLSIDFromString(g_clsid_str[i], &c)) && IsEqualCLSID(c, clsid))
			return i;
	}
	return -1;
}

STDAPI DllGetClassObject(REFCLSID clsid, REFIID riid, void **ppv)
{
	int slot = slot_for_clsid(clsid);
	if (slot < 0)
		return CLASS_E_CLASSNOTAVAILABLE;
	ClassFactory *f = new (std::nothrow) ClassFactory(slot);
	if (!f)
		return E_OUTOFMEMORY;
	HRESULT hr = f->QueryInterface(riid, ppv);
	f->Release();
	return hr;
}

STDAPI DllCanUnloadNow() { return (g_objects == 0 && g_locks == 0) ? S_OK : S_FALSE; }

static HRESULT set_reg(HKEY root, const wchar_t *key, const wchar_t *name, const wchar_t *value)
{
	HKEY k;
	LONG r = RegCreateKeyExW(root, key, 0, nullptr, 0, KEY_WRITE, nullptr, &k, nullptr);
	if (r != ERROR_SUCCESS)
		return HRESULT_FROM_WIN32(r);
	r = RegSetValueExW(k, name, 0, REG_SZ, (const BYTE *)value, (DWORD)((wcslen(value) + 1) * sizeof(wchar_t)));
	RegCloseKey(k);
	return HRESULT_FROM_WIN32(r);
}

STDAPI DllRegisterServer()
{
	wchar_t path[MAX_PATH];
	GetModuleFileNameW(g_module, path, MAX_PATH);
	for (int i = 0; i < VCAM_SLOTS; i++) {
		wchar_t key[160], name[64];
		swprintf_s(key, L"Software\\Classes\\CLSID\\%s", g_clsid_str[i]);
		swprintf_s(name, L"CineRemote Virtual Camera %d", i + 1);
		HRESULT hr = set_reg(HKEY_LOCAL_MACHINE, key, nullptr, name);
		if (FAILED(hr))
			return hr;
		wcscat_s(key, L"\\InprocServer32");
		hr = set_reg(HKEY_LOCAL_MACHINE, key, nullptr, path);
		if (SUCCEEDED(hr))
			hr = set_reg(HKEY_LOCAL_MACHINE, key, L"ThreadingModel", L"Both");
		if (FAILED(hr))
			return hr;
	}
	return S_OK;
}

STDAPI DllUnregisterServer()
{
	for (int i = 0; i < VCAM_SLOTS; i++) {
		wchar_t key[160];
		swprintf_s(key, L"Software\\Classes\\CLSID\\%s", g_clsid_str[i]);
		RegDeleteTreeW(HKEY_LOCAL_MACHINE, key);
	}
	return S_OK;
}

BOOL APIENTRY DllMain(HMODULE h, DWORD reason, LPVOID)
{
	if (reason == DLL_PROCESS_ATTACH) {
		g_module = h;
		DisableThreadLibraryCalls(h);
	}
	return TRUE;
}
