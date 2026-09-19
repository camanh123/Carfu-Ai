package org.stypox.dicio.sherpabenchmark.engine

import java.util.concurrent.atomic.AtomicBoolean

/** Exactly-once STOP/auto-stop/lifecycle finalization. */
class FinalizeGuard {
    private val started = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    fun tryBegin(): Boolean = started.compareAndSet(false, true)

    fun markFinished() {
        finished.set(true)
    }

    fun tryReleaseOnce(): Boolean = released.compareAndSet(false, true)

    fun reset() {
        started.set(false)
        finished.set(false)
        released.set(false)
    }

    val hasStarted: Boolean get() = started.get()
    val hasFinished: Boolean get() = finished.get()
    val hasReleased: Boolean get() = released.get()
}
