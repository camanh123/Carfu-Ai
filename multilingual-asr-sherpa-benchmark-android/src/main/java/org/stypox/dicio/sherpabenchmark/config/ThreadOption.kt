package org.stypox.dicio.sherpabenchmark.config

enum class ThreadOption(val nThreads: Int) {
    ONE(1),
    TWO(2),
    FOUR(4);

    companion object {
        /** Official Zipformer VI example uses num_threads=1. */
        val DEFAULT: ThreadOption = ONE

        val allowed: Set<Int> = setOf(1, 2, 4)

        fun fromCount(n: Int): ThreadOption = when (n) {
            1 -> ONE
            2 -> TWO
            4 -> FOUR
            else -> error("Phase 3B.1 thread options are 1/2/4, got $n")
        }
    }
}
