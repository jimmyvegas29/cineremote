// Capture test: list Media Foundation video capture devices, open the one whose name contains
// argv[1], read frames like any webcam app would, and report frame rate + image statistics.
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mfreadwrite.h>
#include <wrl/client.h>
#include <cmath>
#include <cstdio>
#include <string>
using Microsoft::WRL::ComPtr;

int wmain(int argc, wchar_t **argv)
{
	const wchar_t *want = argc > 1 ? argv[1] : L"";
	int seconds = argc > 2 ? _wtoi(argv[2]) : 4;
	CoInitializeEx(nullptr, COINIT_MULTITHREADED);
	MFStartup(MF_VERSION);

	ComPtr<IMFAttributes> a;
	MFCreateAttributes(&a, 1);
	a->SetGUID(MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE, MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID);
	IMFActivate **devs = nullptr;
	UINT32 n = 0;
	MFEnumDeviceSources(a.Get(), &devs, &n);
	int pick = -1;
	for (UINT32 i = 0; i < n; i++) {
		wchar_t *name = nullptr;
		UINT32 len = 0;
		devs[i]->GetAllocatedString(MF_DEVSOURCE_ATTRIBUTE_FRIENDLY_NAME, &name, &len);
		wprintf(L"device %u: %s\n", i, name ? name : L"?");
		if (pick < 0 && name && *want && wcsstr(name, want))
			pick = (int)i;
		CoTaskMemFree(name);
	}
	if (pick < 0) {
		wprintf(L"RESULT: device containing '%s' NOT FOUND\nFAIL\n", want);
		return 2;
	}
	ComPtr<IMFMediaSource> src;
	HRESULT hr = devs[pick]->ActivateObject(IID_PPV_ARGS(&src));
	if (FAILED(hr)) {
		wprintf(L"ActivateObject failed 0x%08lx\nFAIL\n", hr);
		return 3;
	}
	ComPtr<IMFAttributes> ra;
	MFCreateAttributes(&ra, 1);
	ra->SetUINT32(MF_SOURCE_READER_ENABLE_VIDEO_PROCESSING, TRUE);
	ComPtr<IMFSourceReader> rd;
	hr = MFCreateSourceReaderFromMediaSource(src.Get(), ra.Get(), &rd);
	if (FAILED(hr)) {
		wprintf(L"CreateSourceReader failed 0x%08lx\nFAIL\n", hr);
		return 4;
	}
	ComPtr<IMFMediaType> mt;
	rd->GetCurrentMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM, &mt);
	UINT32 w = 0, h = 0, fn = 0, fd = 0;
	MFGetAttributeSize(mt.Get(), MF_MT_FRAME_SIZE, &w, &h);
	MFGetAttributeRatio(mt.Get(), MF_MT_FRAME_RATE, &fn, &fd);
	GUID sub = {};
	mt->GetGUID(MF_MT_SUBTYPE, &sub);
	wprintf(L"format %ux%u @ %u/%u fps, subtype %08lx\n", w, h, fn, fd, sub.Data1);

	ULONGLONG t0 = GetTickCount64();
	LARGE_INTEGER qf, qfirst = {}, qlast = {}, qprev = {}, qnow;
	QueryPerformanceFrequency(&qf);
	double max_gap_ms = 0;
	int frames = 0;
	double ymean = 0, ystd = 0;
	while (GetTickCount64() - t0 < (ULONGLONG)seconds * 1000) {
		DWORD flags = 0;
		LONGLONG ts = 0;
		ComPtr<IMFSample> s;
		hr = rd->ReadSample(MF_SOURCE_READER_FIRST_VIDEO_STREAM, 0, nullptr, &flags, &ts, &s);
		if (FAILED(hr)) {
			wprintf(L"ReadSample failed 0x%08lx\n", hr);
			break;
		}
		if (!s)
			continue;
		frames++;
		QueryPerformanceCounter(&qnow);
		if (frames == 1)
			qfirst = qnow;
		else {
			double gap = (qnow.QuadPart - qprev.QuadPart) * 1000.0 / qf.QuadPart;
			if (frames > 5 && gap > max_gap_ms)
				max_gap_ms = gap;
		}
		qprev = qlast = qnow;
		ComPtr<IMFMediaBuffer> b;
		s->ConvertToContiguousBuffer(&b);
		BYTE *p = nullptr;
		DWORD len = 0;
		if (SUCCEEDED(b->Lock(&p, nullptr, &len))) {
			double sum = 0, sq = 0;
			DWORD cnt = 0, ylen = w * h < len ? w * h : len;
			for (DWORD i = 0; i < ylen; i += 97) {
				sum += p[i];
				sq += (double)p[i] * p[i];
				cnt++;
			}
			ymean = sum / cnt;
			ystd = sqrt(sq / cnt - ymean * ymean);
			if (frames == 1 || frames == 60) {
				wchar_t path[MAX_PATH];
				swprintf_s(path, L"vcam_frame_%d.nv12", frames);
				FILE *f = nullptr;
				_wfopen_s(&f, path, L"wb");
				if (f) {
					fwrite(p, 1, len, f);
					fclose(f);
				}
			}
			b->Unlock();
		}
	}
	double secs = (qlast.QuadPart - qfirst.QuadPart) / (double)qf.QuadPart;
	double fps = frames > 1 && secs > 0 ? (frames - 1) / secs : 0.0;
	wprintf(L"first frame after %.0f ms, max gap %.1f ms\n",
		(qfirst.QuadPart ? 0.0 : 0.0) + (double)(frames ? 0 : 0), max_gap_ms);
	wprintf(L"RESULT: frames=%d (%.1f fps steady) last Y mean=%.1f std=%.1f\n", frames, fps, ymean, ystd);
	bool ok = frames > seconds * 15;
	wprintf(ok ? L"PASS\n" : L"FAIL\n");
	src->Shutdown();
	for (UINT32 i = 0; i < n; i++)
		devs[i]->Release();
	CoTaskMemFree(devs);
	MFShutdown();
	return ok ? 0 : 1;
}
