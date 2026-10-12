#!/bin/sh
# Build (if needed) and run towerview. Inside `nix-shell` (shell.nix) for the interactive window on NixOS.
#   ./run.sh render ../towergen/layouts/H64max.json --q 1.0 --out /tmp/tv
#   ./run.sh view ../towergen/layouts/H40r3.json --params visual.json
#   ./run.sh params > visual.json
set -e
here=$(cd "$(dirname "$0")" && pwd)
bin="$here/build/install/towerview/bin/towerview"
if [ ! -x "$bin" ] || [ -n "$TOWERVIEW_REBUILD" ]; then
  (cd "$here/.." && ./gradlew --console=plain -q :towerview:installDist >&2)
fi
exec "$bin" "$@"
