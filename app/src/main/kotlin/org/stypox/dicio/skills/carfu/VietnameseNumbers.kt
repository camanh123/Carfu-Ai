package org.stypox.dicio.skills.carfu

/**
 * Local Vietnamese number, duration, and safe-arithmetic parsing.
 *
 * dicio-numbers has no Vietnamese locale, so CARFU cannot use Duration captures or
 * parserFormatter for these skills.
 */
object VietnameseNumbers {
    enum class ArithmeticOp { ADD, SUB, MUL, DIV }

    data class Arithmetic(
        val left: Double,
        val op: ArithmeticOp,
        val right: Double,
    )

    private val ONES = mapOf(
        "khong" to 0L,
        "mot" to 1L,
        "hai" to 2L,
        "ba" to 3L,
        "bon" to 4L,
        "tu" to 4L,
        "nam" to 5L,
        "lam" to 5L,
        "sau" to 6L,
        "bay" to 7L,
        "tam" to 8L,
        "chin" to 9L,
    )

    fun parseInt(folded: String): Long? {
        val trimmed = folded.trim()
        if (trimmed.isEmpty()) return null
        trimmed.toLongOrNull()?.let { return it }
        val tokens = tokenize(trimmed)
        if (tokens.isEmpty()) return null
        val (value, consumed) = parseNumberAt(tokens, 0) ?: return null
        return if (consumed == tokens.size) value else null
    }

    /**
     * Parses one or more `<number> <gio|phút|giây>` groups. In "hẹn giờ 5 phút" the first
     * "giờ" is not a unit because it is not preceded by a parsed number.
     */
    fun parseDurationMs(folded: String): Long? {
        val tokens = tokenize(folded)
        var i = 0
        var total = 0L
        var found = false
        while (i < tokens.size) {
            val parsed = parseNumberAt(tokens, i)
            if (parsed == null) {
                i += 1
                continue
            }
            val (value, consumed) = parsed
            val unitIndex = i + consumed
            if (unitIndex >= tokens.size) break
            val unitMs = when (tokens[unitIndex]) {
                "gio" -> value * 3_600_000L
                "phut" -> value * 60_000L
                "giay" -> value * 1_000L
                else -> null
            }
            if (unitMs != null) {
                total += unitMs
                found = true
                i = unitIndex + 1
            } else {
                i += 1
            }
        }
        return if (found && total > 0L) total else null
    }

    fun parseArithmetic(folded: String): Arithmetic? {
        val tokens = tokenize(folded).filter { it != "tinh" && it != "bang" }
        if (tokens.size < 3) return null
        for (i in tokens.indices) {
            val op = operatorOf(tokens[i]) ?: continue
            var rightStart = i + 1
            if (rightStart < tokens.size && tokens[rightStart] in setOf("voi", "cho")) {
                rightStart += 1
            }
            if (i == 0 || rightStart >= tokens.size) continue
            val left = parseInt(tokens.subList(0, i).joinToString(" "))?.toDouble() ?: continue
            val right = parseInt(tokens.subList(rightStart, tokens.size).joinToString(" "))
                ?.toDouble() ?: continue
            return Arithmetic(left, op, right)
        }
        return null
    }

    fun formatDurationVi(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        val parts = mutableListOf<String>()
        if (hours > 0) parts.add("$hours giờ")
        if (minutes > 0) parts.add("$minutes phút")
        if (seconds > 0 && hours == 0L) parts.add("$seconds giây")
        if (parts.isEmpty()) return "0 giây"
        return parts.joinToString(" ")
    }

    fun formatNumberVi(value: Double): String {
        return if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            val s = "%.2f".format(java.util.Locale.US, value)
            s.trimEnd('0').trimEnd('.')
        }
    }

    private fun operatorOf(token: String): ArithmeticOp? = when (token) {
        "cong", "+" -> ArithmeticOp.ADD
        "tru", "-" -> ArithmeticOp.SUB
        "nhan", "x", "*" -> ArithmeticOp.MUL
        "chia", "/" -> ArithmeticOp.DIV
        else -> null
    }

    private fun tokenize(folded: String): List<String> =
        folded.split(' ').filter { it.isNotEmpty() }

    /**
     * Parses a Vietnamese number starting at [start]. Supports digits, 0–99 word forms,
     * and hundreds ("một trăm hai mươi").
     */
    internal fun parseNumberAt(tokens: List<String>, start: Int): Pair<Long, Int>? {
        if (start >= tokens.size) return null
        tokens[start].toLongOrNull()?.let { return it to 1 }

        var i = start
        var total = 0L
        var consumed = 0

        if (i < tokens.size && tokens[i] == "tram") {
            total += 100L
            i += 1
            consumed = i - start
        }

        val hundreds = onesOf(tokens.getOrNull(i))
        if (hundreds != null && tokens.getOrNull(i + 1) == "tram") {
            total += hundreds * 100L
            i += 2
            consumed = i - start
            if (tokens.getOrNull(i) == "linh" || tokens.getOrNull(i) == "le") {
                i += 1
            }
        }

        val rest = parseBelowHundred(tokens, i) ?: if (consumed > 0) (0L to 0) else null
        if (rest == null) {
            return if (consumed > 0) total to consumed else null
        }
        total += rest.first
        i += rest.second
        consumed = i - start
        return if (consumed > 0) total to consumed else null
    }

    private fun parseBelowHundred(tokens: List<String>, start: Int): Pair<Long, Int>? {
        if (start >= tokens.size) return null
        tokens[start].toLongOrNull()?.let { return it to 1 }

        var i = start
        if (tokens[i] == "muoi") {
            var value = 10L
            i += 1
            val ones = onesOf(tokens.getOrNull(i))
            if (ones != null && ones != 0L) {
                value += ones
                i += 1
            }
            return value to (i - start)
        }

        val tensDigit = onesOf(tokens[i]) ?: return null
        if (tokens.getOrNull(i + 1) == "muoi") {
            var value = tensDigit * 10L
            i += 2
            val ones = onesOf(tokens.getOrNull(i))
            if (ones != null) {
                value += ones
                i += 1
            }
            return value to (i - start)
        }

        return tensDigit to 1
    }

    private fun onesOf(token: String?): Long? {
        if (token == null) return null
        return ONES[token]
    }
}
