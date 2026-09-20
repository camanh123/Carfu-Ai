# Phase 3B.1.2 — controlled performance + memory soak

Interactive START/STOP remains Phase 3B.1.1 simulated-streaming (DecodeGate,
15 s auto-stop, journal, exactly-once finalize). This phase adds a separate
fixed-audio path:

- RECORD REFERENCE stores one 16 kHz mono PCM16 clip in app-private storage
- RUN 1/2/4 BENCHMARK decodes that exact PCM once per run (offline FINAL only)
- 1 warm-up + 3 measured runs per thread count; warm-up excluded from median
- RUN MEMORY SOAK: 10/30/50 sequential iterations, default 30, default threads=1
- No BEST/WINNER. Memory trend is PLATEAU_LIKE / CONTINUING_GROWTH / INCONCLUSIVE
  and never MEMORY_LEAK:YES.

Thermal/order bias may exist (sequence is 1 then 2 then 4).
