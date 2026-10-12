"""Stasis tower library builder (TOWERLIB P3a, offline). See README.md; entry point: python -m towerlib (from tools/)."""
import os
# Single-threaded BLAS before numpy is first imported (the solves run in a process pool; it also keeps results
# reproducible). The original scripts set this at their top.
os.environ["OMP_NUM_THREADS"] = "1"
os.environ["OPENBLAS_NUM_THREADS"] = "1"
