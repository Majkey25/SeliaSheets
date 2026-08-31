package com.majkeylab.seliadocs.editor

import java.math.BigDecimal
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val MAX_EXPRESSION_LENGTH = 256
private const val MAX_PARSE_DEPTH = 64

internal fun evaluateExpression(
    source: String,
    variables: Map<String, Double> = emptyMap(),
): Result<Double> =
    runCatching {
        require(source.length <= MAX_EXPRESSION_LENGTH && source.endsWith('='))
        val expression = source.dropLast(1).replace('×', '*').replace('÷', '/')
        Parser(expression, variables.mapKeys { it.key.lowercase() }).parse().also { require(it.isFinite()) }
    }

internal fun formatMathResult(value: Double): String {
    require(value.isFinite())
    if (value == 0.0) return "0"
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}

internal fun completeTrailingMath(text: String): String {
    val equationEnd = text.indexOfLast { !it.isWhitespace() } + 1
    if (equationEnd == 0) return text
    val expressionStart = text.lastIndexOf('\n', equationEnd - 1) + 1
    val expression = text.substring(expressionStart, equationEnd)
    val value = evaluateExpression(expression, mathVariablesFromText(text.substring(0, expressionStart))).getOrNull()
        ?: return text
    return text.substring(0, equationEnd) + formatMathResult(value) + text.substring(equationEnd)
}

internal fun mathVariablesFromText(text: String): Map<String, Double> {
    val variables = linkedMapOf<String, Double>()
    text.lineSequence().forEach { source ->
        val line = source.trim()
        val separator = line.indexOf('=')
        if (separator <= 0 || separator != line.lastIndexOf('=')) return@forEach
        val name = line.substring(0, separator).trim().lowercase()
        if (!name.isIdentifier()) return@forEach
        evaluateExpression(line.substring(separator + 1).trim() + '=', variables).getOrNull()?.let { value ->
            variables[name] = value
        }
    }
    return variables
}

private fun String.isIdentifier(): Boolean =
    isNotEmpty() && (first().isLetter() || first() == '_') && all { it.isLetterOrDigit() || it == '_' }

private class Parser(
    private val source: String,
    private val variables: Map<String, Double>,
) {
    private var index = 0
    private var depth = 0

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

    private fun parseUnary(): Double {
        require(depth < MAX_PARSE_DEPTH)
        depth++
        return try {
            when {
                match('+') -> parseUnary()
                match('-') -> -parseUnary()
                else -> parsePower()
            }
        } finally {
            depth--
        }
    }

    private fun parsePower(): Double {
        val base = parsePostfix()
        return if (match('^')) base.pow(parseUnary()) else base
    }

    private fun parsePostfix(): Double {
        var value = parsePrimary()
        while (match('%')) value /= 100.0
        return value
    }

    private fun parsePrimary(): Double {
        if (match('(')) {
            val value = parseExpression()
            require(match(')'))
            return value
        }
        skipWhitespace()
        if (index < source.length && (source[index].isLetter() || source[index] == '_')) {
            return parseIdentifierValue()
        }
        return parseNumber()
    }

    private fun parseIdentifierValue(): Double {
        val start = index
        while (index < source.length && (source[index].isLetterOrDigit() || source[index] == '_')) index++
        val name = source.substring(start, index).lowercase()
        if (match('(')) {
            val argument = parseExpression()
            require(match(')'))
            return when (name) {
                "sqrt" -> sqrt(argument)
                "sin" -> sin(argument)
                "cos" -> cos(argument)
                else -> error("Unsupported function")
            }
        }
        return when (name) {
            "pi" -> Math.PI
            "e" -> Math.E
            else -> requireNotNull(variables[name])
        }
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
