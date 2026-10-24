"""CineRemote - entry point (python cineremote.py)."""
import sys

if sys.version_info < (3, 12) and sys.platform.startswith("linux"):
    # PySide6's Linux wheels corrupt None's reference count on Python 3.11 (fatal within seconds)
    sys.exit("CineRemote needs Python 3.12 or newer on Linux (found %d.%d)." % sys.version_info[:2])

from cineremote.gui import main  # noqa: E402

if __name__ == "__main__":
    sys.exit(main())
