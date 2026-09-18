"""Pytest-Pfadkonfiguration für die portable Berichtlogik."""

import sys
from pathlib import Path

MAIN_PYTHON = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(MAIN_PYTHON))
