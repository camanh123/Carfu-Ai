package org.stypox.dicio.resolver.youtube

internal sealed class JsonValue {
    data class Obj(val map: Map<String, JsonValue>) : JsonValue()
    data class Arr(val items: List<JsonValue>) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Num(val value: Double) : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    data object Null : JsonValue()

    fun asObj(): Map<String, JsonValue> = (this as? Obj)?.map ?: emptyMap()
    fun asArr(): List<JsonValue> = (this as? Arr)?.items ?: emptyList()
    fun asString(): String? = (this as? Str)?.value
}

internal class MiniJsonParser(private val text: String) {
    private var i = 0

    fun parse(): JsonValue {
        skipWs()
        val value = parseValue()
        skipWs()
        if (i != text.length) {
            throw IllegalArgumentException("trailing JSON content")
        }
        return value
    }

    private fun parseValue(): JsonValue {
        skipWs()
        if (i >= text.length) throw IllegalArgumentException("unexpected end of JSON")
        return when (val ch = text[i]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue.Str(parseString())
            't' -> parseLiteral("true", JsonValue.Bool(true))
            'f' -> parseLiteral("false", JsonValue.Bool(false))
            'n' -> parseLiteral("null", JsonValue.Null)
            '-', in '0'..'9' -> parseNumber()
            else -> throw IllegalArgumentException("unexpected JSON char '$ch'")
        }
    }

    private fun parseObject(): JsonValue.Obj {
        expect('{')
        val map = linkedMapOf<String, JsonValue>()
        skipWs()
        if (peek('}')) {
            i++
            return JsonValue.Obj(map)
        }
        while (true) {
            skipWs()
            val key = parseString()
            skipWs()
            expect(':')
            map[key] = parseValue()
            skipWs()
            when {
                peek(',') -> {
                    i++
                }
                peek('}') -> {
                    i++
                    break
                }
                else -> throw IllegalArgumentException("expected comma or end of object")
            }
        }
        return JsonValue.Obj(map)
    }

    private fun parseArray(): JsonValue.Arr {
        expect('[')
        val items = mutableListOf<JsonValue>()
        skipWs()
        if (peek(']')) {
            i++
            return JsonValue.Arr(items)
        }
        while (true) {
            items.add(parseValue())
            skipWs()
            when {
                peek(',') -> {
                    i++
                }
                peek(']') -> {
                    i++
                    break
                }
                else -> throw IllegalArgumentException("expected comma or end of array")
            }
        }
        return JsonValue.Arr(items)
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (i < text.length) {
            when (val ch = text[i++]) {
                '"' -> return out.toString()
                '\\' -> {
                    if (i >= text.length) throw IllegalArgumentException("bad escape")
                    when (val esc = text[i++]) {
                        '"', '\\', '/' -> out.append(esc)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (i + 4 > text.length) throw IllegalArgumentException("bad unicode escape")
                            val hex = text.substring(i, i + 4)
                            out.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> throw IllegalArgumentException("bad escape")
                    }
                }
                else -> out.append(ch)
            }
        }
        throw IllegalArgumentException("unterminated string")
    }

    private fun parseNumber(): JsonValue.Num {
        val start = i
        if (peek('-')) i++
        while (i < text.length && text[i] in '0'..'9') i++
        if (peek('.')) {
            i++
            while (i < text.length && text[i] in '0'..'9') i++
        }
        if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
            i++
            if (i < text.length && (text[i] == '+' || text[i] == '-')) i++
            while (i < text.length && text[i] in '0'..'9') i++
        }
        return JsonValue.Num(text.substring(start, i).toDouble())
    }

    private fun parseLiteral(literal: String, value: JsonValue): JsonValue {
        if (!text.startsWith(literal, i)) {
            throw IllegalArgumentException("expected $literal")
        }
        i += literal.length
        return value
    }

    private fun expect(ch: Char) {
        skipWs()
        if (i >= text.length || text[i] != ch) {
            throw IllegalArgumentException("expected '$ch'")
        }
        i++
    }

    private fun peek(ch: Char): Boolean = i < text.length && text[i] == ch

    private fun skipWs() {
        while (i < text.length && text[i].isWhitespace()) i++
    }
}
