# Phase 3B.1.4 — bounded partial decode budget

3B.1.3 OPTIMIZED coalesced requests on CARFU but did **not** reduce executed
full-audio decodes (device fail). That scheduler is preserved as
`OPTIMIZED_3B1_3` and is **not** retuned.

New experimental mode **BOUNDED** (default):

- PARTIAL #1 when captured audio ≥ ~700 ms
- PARTIAL #2+ only when ≥ ~900 ms of **new** audio has arrived since the
  previous executed partial snapshot
- timer polls observe audio; a timer tick alone cannot start a decode
- skip while native decode is active (do not queue a retry storm)
- MAX_PARTIAL_DECODES = 5 (partials only; FINAL is never blocked)
- STOP drops pending partials and runs exactly one FINAL on complete PCM

LEGACY remains the 200 ms device baseline. Recognizer stays warm.
Phase 3B.1.2 controlled benchmark and memory soak are unchanged.

DEVICE_OPTIMIZATION_PASS is not claimed from source tests.
