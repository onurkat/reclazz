#!/usr/bin/env python3
"""Check generated documentation, canonical source coverage and evidence links."""
from pathlib import Path
import sys
sys.dont_write_bytecode = True
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from website.check import main
if __name__ == '__main__': main()
