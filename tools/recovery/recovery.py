"""Einstiegspunkt: python tools/recovery/recovery.py <befehl> ... (siehe README.md)."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from pcerecovery.cli import main  # noqa: E402

if __name__ == "__main__":
    main()
