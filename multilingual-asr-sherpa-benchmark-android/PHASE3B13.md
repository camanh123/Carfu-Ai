# Phase 3B.1.3 — interactive decode optimization

A/B interactive simulated-streaming on the existing offline Zipformer.
Does not change ASR, model, decoder, hashes, or production VIA/CARFU.

## Modes

- **LEGACY**: 200 ms tick → DecodeGate → full accumulated re-decode
  (Phase 3B.1.1 / 3B.1.2 path, kept for comparison).
- **OPTIMIZED** (default): first partial after ~600 ms of audio; later partials
  only when the previous decode finished **and** adaptive new-audio has arrived.
  Pending ≤ 1. Newest pending snapshot is coalesced. STOP discards obsolete
  pending partials and runs exactly one FINAL on complete PCM.

Partials stay revisable RAW (never concatenated / monotonic-forced).
Recognizer stays warm unless thread count changes.
Fixed-audio thread benchmark and memory soak are unchanged.

Default device UI: OPTIMIZED, 4 threads.

DEVICE_OPTIMIZATION_PASS is not claimed from source tests.
Device A/B comparison decides.
