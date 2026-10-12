#!/bin/sh
# Rebuild the tower library and/or goldens and install them into towergen.
#   tools/towerlib/build_library.sh export    re-export the library from the solve cache (default; seconds)
#   tools/towerlib/build_library.sh goldens   the 7 test layouts (solving missing keys) + goldens
#   tools/towerlib/build_library.sh full      everything from the (possibly empty) cache: ~30 min on 8 processes
# Then copies OUT/p3a_library.json.gz -> towergen/src/main/resources/towergen/ (export, full) and
# OUT/p3a_goldens.json.gz -> towergen/src/test/resources/ (goldens, full), and runs :towergen:libraryBin (TGLB1 for
# towerweb) and :towergen:test. Review `git diff --stat` before committing.
# Env: TOWERLIB_CACHE, TOWERLIB_OUT (see paths.py), P3A_PROCS, GRADLE (default: ./gradlew; on the lab box
# GRADLE=/var/lib/mclab/agent/tools/stasis-build.sh), NO_INSTALL=1 (build only).
# Python deps (numpy, scipy >= 1.6 for HiGHS) come from nixpkgs:
#   nix-shell -p 'python3.withPackages(p: [p.numpy p.scipy])'
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
MODE=${1:-export}
OUT=${TOWERLIB_OUT:-$HERE/out}
export TOWERLIB_OUT="$OUT"
case $MODE in export|goldens|full) ;; *) echo "usage: $0 [export|goldens|full]" >&2; exit 2;; esac
CACHE=${TOWERLIB_CACHE:-$HERE/cache}
n=$(ls "$CACHE" 2>/dev/null | grep -c '\.pkl$' || true)
if [ "$MODE" = export ] && [ "$n" -lt 7000 ] && [ -z "$FORCE" ]; then
  echo "cache $CACHE has $n entries (the shipped library has 7747): run 'full' first, or point TOWERLIB_CACHE at a" >&2
  echo "complete cache (e.g. timestop-proto/towerlib/p3acache); FORCE=1 to export anyway" >&2; exit 1
fi
cd "$REPO/tools"
if command -v nix-shell >/dev/null 2>&1; then
  nix-shell -p 'python3.withPackages(p: [p.numpy p.scipy])' --run "python3 -m towerlib $MODE"
else
  python3 -m towerlib "$MODE"
fi
[ -n "$NO_INSTALL" ] && exit 0
cd "$REPO"
case $MODE in export|full) cp "$OUT/p3a_library.json.gz" towergen/src/main/resources/towergen/p3a_library.json.gz;; esac
case $MODE in goldens|full) cp "$OUT/p3a_goldens.json.gz" towergen/src/test/resources/p3a_goldens.json.gz;; esac
${GRADLE:-./gradlew} :towergen:libraryBin :towergen:test
echo "installed; TGLB1: towergen/build/libbin/p3a_library.bin.gz (towerweb dist picks it up)"
