#!/bin/bash
# Convenience wrapper: ensures hidapi (Homebrew) is on the library path.
export DYLD_LIBRARY_PATH="/opt/homebrew/lib:${DYLD_LIBRARY_PATH}"
exec python3 "$(dirname "$0")/activate.py" "$@"
