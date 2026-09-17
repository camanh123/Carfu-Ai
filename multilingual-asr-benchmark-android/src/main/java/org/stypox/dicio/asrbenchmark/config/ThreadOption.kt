package org.stypox.dicio.asrbenchmark.config

enum class ThreadOption(val nThreads: Int, val label: String) {
    TWO(2, "2 threads"),
    FOUR(4, "4 threads");

    companion object {
        /** Default 4 on T610 (8 cores: 2x A75 + 6x A55). Conservative vs using all cores. */
        val DEFAULT: ThreadOption = FOUR

        fun fromCount(n: Int): ThreadOption = if (n <= 2) TWO else FOUR
    }
}
