package com.saeed.ikev2vpn.profile

/** Android JSONTokener is lenient. Validate untrusted share JSON before handing it to that parser. */
internal class ShareJsonValidator(private val text: String) {
    private var position = 0

    fun validate() {
        value(0)
        whitespace()
        if (position != text.length) invalid()
    }

    private fun value(depth: Int) {
        if (depth > 32) invalid()
        whitespace()
        when (peek()) {
            '{' -> {
                position++
                whitespace()
                if (consume('}')) return
                val keys = HashSet<String>()
                do {
                    whitespace()
                    val key = string()
                    if (!keys.add(key)) invalid()
                    whitespace()
                    if (!consume(':')) invalid()
                    value(depth + 1)
                    whitespace()
                    if (consume('}')) return
                } while (consume(','))
                invalid()
            }
            '[' -> {
                position++
                whitespace()
                if (consume(']')) return
                do {
                    value(depth + 1)
                    whitespace()
                    if (consume(']')) return
                } while (consume(','))
                invalid()
            }
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            '-', in '0'..'9' -> number()
            else -> invalid()
        }
    }

    private fun string(): String {
        if (!consume('"')) invalid()
        val result = StringBuilder()
        while (position < text.length) {
            val character = text[position++]
            when {
                character == '"' -> return result.toString()
                character == '\\' -> {
                    val escaped = peek()
                    position++
                    result.append(when (escaped) {
                        '"', '\\', '/' -> escaped
                        'b' -> '\b'
                        'f' -> '\u000C'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'u' -> {
                            if (position + 4 > text.length) invalid()
                            var code = 0
                            repeat(4) {
                                val digit = text[position++]
                                if (digit !in "0123456789abcdefABCDEF") invalid()
                                code = code * 16 + digit.digitToInt(16)
                            }
                            code.toChar()
                        }
                        else -> invalid()
                    })
                }
                character < ' ' -> invalid()
                else -> result.append(character)
            }
        }
        invalid()
    }

    private fun number() {
        consume('-')
        if (!consume('0')) digits()
        if (consume('.')) digits()
        if (consume('e') || consume('E')) {
            if (!consume('+')) consume('-')
            digits()
        }
    }

    private fun digits() {
        val start = position
        while (peek() in '0'..'9') position++
        if (start == position) invalid()
    }

    private fun literal(value: String) {
        if (!text.startsWith(value, position)) invalid()
        position += value.length
    }

    private fun whitespace() {
        while (peek() == ' ' || peek() == '\t' || peek() == '\r' || peek() == '\n') position++
    }

    private fun consume(character: Char): Boolean {
        if (peek() != character) return false
        position++
        return true
    }

    private fun peek(): Char = text.getOrNull(position) ?: '\u0000'
    private fun invalid(): Nothing = throw IkevProfileImportException("The configuration link contains invalid or excessively nested JSON.")
}
