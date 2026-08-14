#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
if command -v gradle >/dev/null 2>&1; then
  gradle testDebugUnitTest assembleDebug
else
  echo "Gradle is not installed in Termux. Install a compatible Gradle distribution or build through GitHub Actions." >&2
  exit 127
fi
