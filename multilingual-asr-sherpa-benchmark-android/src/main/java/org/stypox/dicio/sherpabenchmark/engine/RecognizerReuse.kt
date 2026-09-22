package org.stypox.dicio.sherpabenchmark.engine

/** Recognizer stays warm across commands unless thread count changes. */
object RecognizerReuse {
    fun needsRecreate(ready: Boolean, loadedThreads: Int, requestedThreads: Int): Boolean =
        !ready || loadedThreads != requestedThreads
}
