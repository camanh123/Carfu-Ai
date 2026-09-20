package org.stypox.dicio.sherpabenchmark.engine

object Stats {
    fun medianLong(values: List<Long>): Long {
        require(values.isNotEmpty()) { "median of empty list" }
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2L
    }

    fun minLong(values: List<Long>): Long = values.minOrNull() ?: 0L

    fun maxLong(values: List<Long>): Long = values.maxOrNull() ?: 0L

    fun medianDouble(values: List<Double>): Double {
        require(values.isNotEmpty()) { "median of empty list" }
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }

    fun minDouble(values: List<Double>): Double = values.minOrNull() ?: 0.0

    fun maxDouble(values: List<Double>): Double = values.maxOrNull() ?: 0.0
}
