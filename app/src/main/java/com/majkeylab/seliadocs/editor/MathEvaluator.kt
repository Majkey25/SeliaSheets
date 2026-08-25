package com.majkeylab.seliadocs.editor

import java.math.BigDecimal
import kotlin.math.pow

private const val MAX_EXPRESSION_LENGTH = 256

internal fun evaluateExpression(source: String): Result<Double> =
    runCatching {
        require(source.length <= MAX_EXPRESSION_LENGTH && source.endsWith('='))
        val expression = source.dropLast(1).replace('×', '*').replace('÷', '/')
        Parser(expression).parse().also { require(it.isFinite()) }
    }

internal fun formatMathResult(value: Double): String {
    require(value.isFinite())
    if (value == 0.0) return "0"
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}

private class Parser(private val source: String) {
    private var index = 0

    fun parse(): Double {
        val value = parseExpression()
        skipWhitespace()
        require(index == source.length)
        return value
    }

    private fun parseExpression(): Double {
        var value = parseTerm()
        while (true) {
            value =
                when {
                    match('+') -> value + parseTerm()
                    match('-') -> value - parseTerm()
                    else -> return value
                }
        }
    }

    private fun parseTerm(): Double {
        var value = parseUnary()
        while (true) {
            value =
                when {
                    match('*') -> value * parseUnary()
                    match('/') -> {
                        val divisor = parseUnary()
                        require(divisor != 0.0)
                        value / divisor
                    }
                    else -> return value
                }
        }
    }

    private fun parseUnary(): Double =
        when {
            match('+') -> parseUnary()
            match('-') -> -parseUnary()
            else -> parsePower()
        }

    private fun parsePower(): Double {
        val base = parsePrimary()
        return if (match('^')) base.pow(parseUnary()) else base
    }

    private fun parsePrimary(): Double {
        if (match('(')) {
            val value = parseExpression()
            require(match(')'))
            return value
        }
        return parseNumber()
    }

    private fun parseNumber(): Double {
        skipWhitespace()
        val start = index
        var digitSeen = false
        var dotSeen = false
        while (index < source.length) {
            val character = source[index]
            when {
                character.isDigit() -> {
                    digitSeen = true
                    index++
                }
                character == '.' && !dotSeen -> {
                    dotSeen = true
                    index++
                }
                else -> break
            }
        }
        require(digitSeen)
        return source.substring(start, index).toDouble()
    }

    private fun match(expected: Char): Boolean {
        skipWhitespace()
        if (index >= source.length || source[index] != expected) return false
        index++
        return true
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index].isWhitespace()) index++
    }
}
