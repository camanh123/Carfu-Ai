package org.stypox.dicio.sherpabenchmark.engine

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Coalescing decode gate: at most one in-flight decode and at most one pending
 * follow-up. Incoming ticks never enqueue an unbounded backlog.
 */
class DecodeGate {
    private val inFlight = AtomicBoolean(false)
    private val pending = AtomicBoolean(false)
    private val executorQueued = AtomicInteger(0)
    private val submitted = AtomicInteger(0)
    private val coalesced = AtomicInteger(0)

    val pendingCount: Int get() = if (pending.get()) 1 else 0
    val inFlightCount: Int get() = if (inFlight.get()) 1 else 0
    val queuedRunnableCount: Int get() = executorQueued.get()
    val submittedCount: Int get() = submitted.get()
    val coalescedCount: Int get() = coalesced.get()

    /**
     * @return true if [work] was scheduled on [executor]; false if coalesced into pending.
     */
    fun trySchedule(executor: (Runnable) -> Unit, work: () -> Unit): Boolean {
        if (!inFlight.compareAndSet(false, true)) {
            pending.set(true)
            coalesced.incrementAndGet()
            return false
        }
        submitted.incrementAndGet()
        executorQueued.incrementAndGet()
        executor {
            try {
                do {
                    pending.set(false)
                    work()
                } while (pending.get())
            } finally {
                executorQueued.decrementAndGet()
                inFlight.set(false)
                if (pending.getAndSet(false)) {
                    trySchedule(executor, work)
                }
            }
        }
        return true
    }

    fun reset() {
        inFlight.set(false)
        pending.set(false)
        executorQueued.set(0)
        submitted.set(0)
        coalesced.set(0)
    }
}
