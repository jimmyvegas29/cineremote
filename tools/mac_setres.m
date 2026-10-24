// mac_setres: list / switch the main display mode on macOS (incl. non-Retina "low resolution" modes).
// Used to make VNC sessions fit: a Retina Mac otherwise sends its full backing resolution.
//   clang -framework CoreGraphics -framework Foundation mac_setres.m -o mac_setres
//   ./mac_setres                 list modes (W x H, HiDPI flag, refresh)
//   ./mac_setres 1512 982 lo     switch to 1512x982 non-HiDPI (VNC-friendly)
//   ./mac_setres 1512 982 hi     back to the Retina default ("looks like" 1512x982)
// Must run inside the logged-in user's GUI session (from SSH: sudo launchctl asuser <uid> ...).
#import <CoreGraphics/CoreGraphics.h>
#import <Foundation/Foundation.h>

int main(int argc, const char **argv)
{
	@autoreleasepool {
		CGDirectDisplayID d = CGMainDisplayID();
		NSDictionary *opts = @{(__bridge NSString *)kCGDisplayShowDuplicateLowResolutionModes: @YES};
		CFArrayRef modes = CGDisplayCopyAllDisplayModes(d, (__bridge CFDictionaryRef)opts);
		CGDisplayModeRef cur = CGDisplayCopyDisplayMode(d);
		printf("current: %zux%zu (pixels %zux%zu)\n", CGDisplayModeGetWidth(cur), CGDisplayModeGetHeight(cur),
		       CGDisplayModeGetPixelWidth(cur), CGDisplayModeGetPixelHeight(cur));
		long want_w = argc > 2 ? atol(argv[1]) : 0, want_h = argc > 2 ? atol(argv[2]) : 0;
		bool want_hidpi = argc > 3 && strcmp(argv[3], "hi") == 0;
		CGDisplayModeRef pick = NULL;
		for (CFIndex i = 0; i < CFArrayGetCount(modes); i++) {
			CGDisplayModeRef m = (CGDisplayModeRef)CFArrayGetValueAtIndex(modes, i);
			size_t w = CGDisplayModeGetWidth(m), h = CGDisplayModeGetHeight(m);
			bool hidpi = CGDisplayModeGetPixelWidth(m) > w;
			if (!want_w)
				printf("  %4zu x %-4zu %s %.0fHz\n", w, h, hidpi ? "HiDPI" : "     ", CGDisplayModeGetRefreshRate(m));
			else if ((long)w == want_w && (long)h == want_h && hidpi == want_hidpi && !pick)
				pick = m;
		}
		if (want_w) {
			if (!pick) {
				fprintf(stderr, "no %ldx%ld %s mode\n", want_w, want_h, want_hidpi ? "HiDPI" : "low-res");
				return 1;
			}
			CGDisplayConfigRef cfg;
			CGBeginDisplayConfiguration(&cfg);
			CGConfigureDisplayWithDisplayMode(cfg, d, pick, NULL);
			CGError e = CGCompleteDisplayConfiguration(cfg, kCGConfigurePermanently);
			printf("switch -> %ldx%ld %s: %s\n", want_w, want_h, want_hidpi ? "HiDPI" : "low-res", e ? "FAILED" : "ok");
			return e ? 1 : 0;
		}
	}
	return 0;
}
