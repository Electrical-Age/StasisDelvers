"""Where the builder reads and writes. One solve cache (every bay / boom solve, one pickle per key: sha1(keystr)[:16])
and one output directory (logs, raw stitches, library, goldens).
  TOWERLIB_CACHE  default tools/towerlib/cache  (point it at an existing cache, e.g. timestop-proto/towerlib/p3acache,
                  to re-export without solving)
  TOWERLIB_OUT    default tools/towerlib/out
Both defaults are git-ignored."""
import os

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.abspath(os.environ.get('TOWERLIB_CACHE') or os.path.join(HERE, 'cache'))
OUT = os.path.abspath(os.environ.get('TOWERLIB_OUT') or os.path.join(HERE, 'out'))
os.makedirs(CACHE, exist_ok=True)
os.makedirs(OUT, exist_ok=True)
