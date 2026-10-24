// cineremote-vcam-host: creates/removes Windows 11 virtual cameras on behalf of the Python app.
//
// The app launches this as a child process and drives it over stdin (UTF-8 lines):
//   add <slot> <friendly name>     -> "ok add <slot>" | "err add <slot> 0x<hr>"
//   remove <slot> <friendly name>  -> "ok remove <slot>"   (unregisters that camera)
//   quit
// A registration is identified by (friendly name, CLSID): adding the same pair again - from any
// process - reopens the existing device, while a different name on the same slot registers a second
// device. So renaming = remove(old name) + add(new name), and the app remembers the names it used.
// Default: MFVirtualCameraLifetime_System + CurrentUser access (no admin needed). The cameras stay
// registered after this process exits, so an app holding one (OBS, Teams) keeps a valid device
// across restarts of ours and just sees black until frames flow again. "--session" gives the old
// behaviour (cameras vanish with this process). stdin EOF / quit releases without unregistering.

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <mfapi.h>
#include <mfvirtualcamera.h>
#include <cstdio>
#include <cstring>
#include <string>
#include "vcam_shared.h"

static const wchar_t *g_clsid[VCAM_SLOTS] = {VCAM_CLSID_STR0, VCAM_CLSID_STR1, VCAM_CLSID_STR2, VCAM_CLSID_STR3};
static IMFVirtualCamera *g_cams[VCAM_SLOTS] = {};
static MFVirtualCameraLifetime g_lifetime = MFVirtualCameraLifetime_System;

static void out(const char *fmt, ...)
{
	va_list ap;
	va_start(ap, fmt);
	vprintf(fmt, ap);
	va_end(ap);
	printf("\n");
	fflush(stdout);
}

static void release_cam(int slot, bool unregister)
{
	if (g_cams[slot]) {
		if (unregister || g_lifetime == MFVirtualCameraLifetime_Session)
			g_cams[slot]->Remove();
		g_cams[slot]->Release();
		g_cams[slot] = nullptr;
	}
}

static HRESULT create_cam(int slot, const wchar_t *name, IMFVirtualCamera **cam)
{
	return MFCreateVirtualCamera(MFVirtualCameraType_SoftwareCameraSource, g_lifetime, MFVirtualCameraAccess_CurrentUser,
				     name, g_clsid[slot], nullptr, 0, cam);
}

int main(int argc, char **argv)
{
	for (int i = 1; i < argc; i++)
		if (strcmp(argv[i], "--session") == 0)
			g_lifetime = MFVirtualCameraLifetime_Session;
	HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
	if (SUCCEEDED(hr))
		hr = MFStartup(MF_VERSION);
	if (FAILED(hr)) {
		out("fatal startup 0x%08lx", hr);
		return 1;
	}
	BOOL supported = FALSE;
	MFIsVirtualCameraTypeSupported(MFVirtualCameraType_SoftwareCameraSource, &supported);
	out("ready supported=%d", supported ? 1 : 0);

	auto parse = [](char *p, int *slot, wchar_t *wname) -> bool {
		*slot = atoi(p);
		char *name = strchr(p, ' ');
		if (*slot < 0 || *slot >= VCAM_SLOTS || !name || !name[1])
			return false;
		if (!MultiByteToWideChar(CP_UTF8, 0, name + 1, -1, wname, 127) || !wname[0])
			return false;
		wname[127] = 0;
		return true;
	};
	char line[1024];
	while (fgets(line, sizeof(line), stdin)) {
		size_t n = strlen(line);
		while (n && (line[n - 1] == '\n' || line[n - 1] == '\r'))
			line[--n] = 0;
		int slot = -1;
		if (strncmp(line, "add ", 4) == 0) {
			wchar_t wname[128] = {};
			if (!parse(line + 4, &slot, wname)) {
				out("err add %d bad-args", slot);
				continue;
			}
			release_cam(slot, false);
			IMFVirtualCamera *cam = nullptr;
			hr = create_cam(slot, wname, &cam);
			if (SUCCEEDED(hr))
				hr = cam->Start(nullptr);
			if (FAILED(hr)) {
				if (cam) {
					cam->Remove();
					cam->Release();
				}
				out("err add %d 0x%08lx", slot, hr);
				continue;
			}
			g_cams[slot] = cam;
			out("ok add %d", slot);
		} else if (strncmp(line, "remove ", 7) == 0) {
			wchar_t wname[128] = {};
			if (!parse(line + 7, &slot, wname)) {
				out("err remove %d bad-args", slot);
				continue;
			}
			release_cam(slot, false);
			IMFVirtualCamera *cam = nullptr; /* reopens the registration (possibly from an earlier run) */
			hr = create_cam(slot, wname, &cam);
			if (SUCCEEDED(hr)) {
				hr = cam->Remove();
				cam->Release();
			}
			if (FAILED(hr))
				out("err remove %d 0x%08lx", slot, hr);
			else
				out("ok remove %d", slot);
		} else if (strcmp(line, "quit") == 0) {
			break;
		} else if (n) {
			out("err unknown-command");
		}
	}
	for (int i = 0; i < VCAM_SLOTS; i++)
		release_cam(i, false);
	MFShutdown();
	CoUninitialize();
	return 0;
}
