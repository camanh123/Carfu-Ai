# Phase 3B.1.1 — simulated-streaming audit (from source)

Device evidence accepted: the model runs on CARFU and produced useful Vietnamese
partials. Crash/exit around ~36s recording is the bug. Partial revision is not a bug.

## What happens per microphone read

`Pcm16kMonoRecorder` uses `AudioRecord.read` into a buffer of
`getMinBufferSize(16 kHz, mono, PCM16) * 4`. That size is **device-dependent**,
not a fixed 200 ms. Each successful read is appended to a `ByteArrayOutputStream`.

The 200 ms figure (`HardFreeze.SIMULATED_STREAMING_CHUNK_MS`) is the **UI/decode
tick**, not the capture chunk.

## Phase 3B.1 behavior (pre-fix)

| Question | Answer from source |
| --- | --- |
| Audio accumulated indefinitely? | YES — `ByteArrayOutputStream` until STOP (`Pcm16kMonoRecorder`) |
| Every partial reprocesses ALL audio? | YES — `SimulatedStreamingDecoder.tryPartial` passes the full `FloatArray` to `SherpaOfflineBackend.decode` |
| Only new audio processed? | NO — `lastDecodedSampleCount` only gates *whether* to decode, not *what* is decoded |
| Stream recreated per decode? | YES — `createStream` / `acceptWaveform` / `decode` / `getResult` / `release` |
| Recognizer recreated per chunk? | NO — one `OfflineRecognizer` per thread-count |
| Kotlin arrays retained? | YES — full PCM plus a new `toByteArray()` + `FloatArray` copy on every decode attempt |
| Decode jobs overlap natively? | NO on the single worker thread, but... |
| UI can enqueue faster than decode? | YES — `MainActivity.startTickers` did `worker.execute { maybePartial() }` every 200 ms with no gate |
| Unbounded backlog? | YES — `Executor` queue grew while full-utterance decode time increased with duration |
| STOP race with decode? | YES — STOP queued behind backlog; `onDestroy` used `shutdownNow()` + `backend.release()` |
| Activity can release native during decode? | YES — `onDestroy` `shutdownNow()` then `backend.release()` |

That backlog of full-utterance CPU INT8 decodes is the source root cause of the
long-session exit (CPU/memory pressure and/or native use-after-free on destroy).

## Phase 3B.1.1 controls

- `DecodeGate`: at most one in-flight decode and one coalesced pending
- Mic `requestStop()` at STOP / 15 s auto-stop before waiting for decode
- `FinalizeGuard`: exactly-once finalize, no double release
- `onDestroy`: orderly `worker.shutdown()` + `awaitTermination` then release recognizer
- Persistent bounded journal + uncaught Java exception handler
- Native SIGSEGV/SIGABRT still cannot be captured by the Java handler
