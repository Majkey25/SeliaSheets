package cz.majkey.perko.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MathEvaluatorTest {
    @Test
    fun precedenceAndParentheses() {
        assertEquals(14.0, evaluateExpression("2+3*4=").getOrThrow(), 0.0)
        assertEquals(20.0, evaluateExpression("(2+3)*4=").getOrThrow(), 0.0)
    }

    @Test
    fun unicodeOperatorsAndRightAssociativePower() {
        assertEquals(7.0, evaluateExpression("18÷3+1=").getOrThrow(), 0.0)
        assertEquals(6.0, evaluateExpression("2×3=").getOrThrow(), 0.0)
        assertEquals(512.0, evaluateExpression("2^3^2=").getOrThrow(), 0.0)
    }

    @Test
    fun unaryMinusHasLowerPrecedenceThanPower() {
        assertEquals(-4.0, evaluateExpression("-2^2=").getOrThrow(), 0.0)
        assertEquals(0.25, evaluateExpression("2^-2=").getOrThrow(), 0.0)
    }

    @Test
    fun invalidInputsFailWithoutResult() {
        listOf("1/0=", "2+=", "two=", "1", "=", "1".repeat(257) + "=").forEach { value ->
            assertTrue(value, evaluateExpression(value).isFailure)
        }
    }

    @Test
    fun resultsUsePlainReadableNumbers() {
        assertEquals("5", formatMathResult(5.0))
        assertEquals("2.5", formatMathResult(2.5))
        assertEquals("0", formatMathResult(-0.0))
    }
}
