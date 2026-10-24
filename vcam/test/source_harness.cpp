// In-process harness for cineremote-vcam.dll: activates the media source the way Frame Server does and
// pulls samples, printing every HRESULT. Usage: source_harness.exe <path-to-dll> [samples]
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mferror.h>
#include <wrl/client.h>
#include <cstdio>
#include "../src/vcam_shared.h"
using Microsoft::WRL::ComPtr;

#define CHECK(x)                                                              \
	do {                                                                      \
		HRESULT _hr = (x);                                                    \
		wprintf(L"%-60S -> 0x%08lx\n", #x, _hr);                              \
		if (FAILED(_hr))                                                      \
			return 1;                                                         \
	} while (0)

typedef HRESULT(STDAPICALLTYPE *GetClassObjectFn)(REFCLSID, REFIID, void **);

static HRESULT wait_event(IMFMediaEventGenerator *g, MediaEventType want, IMFMediaEvent **out, DWORD ms)
{
	ULONGLONG t0 = GetTickCount64();
	while (GetTickCount64() - t0 < ms) {
		ComPtr<IMFMediaEvent> e;
		HRESULT hr = g->GetEvent(MF_EVENT_FLAG_NO_WAIT, &e);
		if (hr == MF_E_NO_EVENTS_AVAILABLE) {
			Sleep(2);
			continue;
		}
		if (FAILED(hr))
			return hr;
		MediaEventType t;
		e->GetType(&t);
		HRESULT st;
		e->GetStatus(&st);
		wprintf(L"  event %lu status 0x%08lx\n", t, st);
		if (t == want) {
			*out = e.Detach();
			return S_OK;
		}
	}
	return HRESULT_FROM_WIN32(WAIT_TIMEOUT);
}

int wmain(int argc, wchar_t **argv)
{
	int samples = argc > 2 ? _wtoi(argv[2]) : 30;
	CoInitializeEx(nullptr, COINIT_MULTITHREADED);
	MFStartup(MF_VERSION);
	HMODULE dll = LoadLibraryW(argv[1]);
	if (!dll) {
		wprintf(L"LoadLibrary failed %lu\n", GetLastError());
		return 1;
	}
	auto get = (GetClassObjectFn)GetProcAddress(dll, "DllGetClassObject");
	CLSID clsid;
	CLSIDFromString(VCAM_CLSID_STR0, &clsid);
	ComPtr<IClassFactory> cf;
	CHECK(get(clsid, IID_PPV_ARGS(&cf)));
	ComPtr<IMFActivate> act;
	CHECK(cf->CreateInstance(nullptr, IID_PPV_ARGS(&act)));
	ComPtr<IMFMediaSource> src;
	CHECK(act->ActivateObject(IID_PPV_ARGS(&src)));
	DWORD ch = 0;
	CHECK(src->GetCharacteristics(&ch));
	ComPtr<IMFMediaSourceEx> srcex;
	CHECK(src.As(&srcex));
	ComPtr<IMFAttributes> sa;
	CHECK(srcex->GetStreamAttributes(0, &sa));
	ComPtr<IMFPresentationDescriptor> pd;
	CHECK(src->CreatePresentationDescriptor(&pd));
	PROPVARIANT var;
	PropVariantInit(&var);
	CHECK(src->Start(pd.Get(), nullptr, &var));
	ComPtr<IMFMediaEvent> ev;
	CHECK(wait_event(src.Get(), MENewStream, &ev, 2000));
	PROPVARIANT v;
	PropVariantInit(&v);
	ev->GetValue(&v);
	ComPtr<IMFMediaStream> stream;
	CHECK(v.punkVal->QueryInterface(IID_PPV_ARGS(&stream)));
	PropVariantClear(&v);
	CHECK(wait_event(stream.Get(), MEStreamStarted, &ev, 2000));
	ULONGLONG t0 = GetTickCount64();
	int got = 0;
	for (int i = 0; i < samples; i++) {
		HRESULT hr = stream->RequestSample(nullptr);
		if (FAILED(hr)) {
			wprintf(L"RequestSample failed 0x%08lx\n", hr);
			return 1;
		}
		ComPtr<IMFMediaEvent> se;
		hr = wait_event(stream.Get(), MEMediaSample, &se, 1000);
		if (FAILED(hr)) {
			wprintf(L"no sample 0x%08lx\n", hr);
			return 1;
		}
		got++;
	}
	double secs = (GetTickCount64() - t0) / 1000.0;
	wprintf(L"RESULT: %d samples in %.2fs (%.1f/s)\n", got, secs, got / secs);
	CHECK(src->Stop());
	CHECK(src->Shutdown());
	act->ShutdownObject();
	wprintf(L"PASS\n");
	return 0;
}
