#!/usr/bin/env python3
"""Build the website from main/docs; use --check to reject stale output."""
from pathlib import Path
import sys
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from website.build import main
if __name__ == '__main__':
    main()
