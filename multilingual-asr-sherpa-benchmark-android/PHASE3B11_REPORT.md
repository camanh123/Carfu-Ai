PHASE_PATCH: 3B.1.1
DEVICE_EVIDENCE_ACCEPTED: YES
SOURCE_ROOT_CAUSE_FOUND: YES
SOURCE_ROOT_CAUSE: UI decode ticker enqueued a full-utterance OfflineRecognizer decode every 200ms with no backpressure; decode time grew with audio duration so the worker queue and native work exploded; onDestroy could shutdownNow+release the recognizer during decode.

AUDIO_CHUNK_DURATION: AudioRecord minBuffer*4 (device-dependent); UI/decode tick 200ms
AUDIO_BUFFER_GROWTH: YES until STOP or 15s auto-stop (PCM ByteArrayOutputStream)
DECODE_QUEUE_GROWTH: YES in 3B.1; NO after DecodeGate (pending<=1)
NATIVE_OBJECT_GROWTH: Stream recreated per decode then released; recognizer reused
RESULT_HISTORY_GROWTH: YES in 3B.1; bounded to 32 partial events in 3B.1.1
COROUTINE_OR_THREAD_GROWTH: NO (fixed mic + worker + peak sampler)
OVERLAPPING_DECODE: NO native overlap on single worker; 3B.1 queued many serial full decodes
RECOGNIZER_RECREATION: NO per chunk
STREAM_RECREATION: YES per decode (createStream/release)

BACKPRESSURE_STRATEGY: DecodeGate coalesce; mic requestStop at finalize; 15s auto-stop
MAX_RECORDING_DURATION: 15000 ms
AUTO_STOP_FINALIZES_NORMALLY: YES

PERSISTENT_SESSION_JOURNAL: YES
UNCAUGHT_EXCEPTION_CAPTURE: YES (Java/Kotlin)
NATIVE_CRASH_CAPTURE_LIMITATION: SIGSEGV/SIGABRT not captured by the Java handler

MODEL_UNCHANGED: YES
MODEL_HASHES_UNCHANGED: YES
SHERPA_VERSION_UNCHANGED: YES
DECODING_UNCHANGED: YES
HOTWORDS_CONNECTED: NO
PHASE2A_CONNECTED: NO
NLU_CONNECTED: NO
PRODUCTION_CONNECTED: NO

TESTS/BUILD/APK: filled after assemble+audit
