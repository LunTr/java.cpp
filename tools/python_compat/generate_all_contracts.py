"""Regenerate all ignored Python fixtures from a verified pinned source snapshot."""

import argparse
import pathlib
import subprocess
import sys

parser = argparse.ArgumentParser()
parser.add_argument("--source-root", type=pathlib.Path, required=True)
args = parser.parse_args()
directory = pathlib.Path(__file__).resolve().parent
for name in ["generate_bridge_contracts.py", "generate_chat_contracts.py", "generate_state_contracts.py"]:
    subprocess.run([sys.executable, str(directory / name), "--source-root", str(args.source_root.resolve())], check=True)
