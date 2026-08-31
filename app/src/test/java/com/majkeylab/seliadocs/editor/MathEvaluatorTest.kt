package com.majkeylab.seliadocs.editor

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
    fun percentagesAndFunctionsEvaluateInline() {
        assertEquals(20.0, evaluateExpression("200*10%=").getOrThrow(), 0.0)
        assertEquals(10.0, evaluateExpression("sqrt(81)+cos(0)=").getOrThrow(), 0.000_001)
        assertEquals(1.0, evaluateExpression("sin(pi/2)=").getOrThrow(), 0.000_001)
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

    @Test
    fun trailingEquationCompletesInNormalPageText() {
        assertEquals("Lecture notes\n2+3*4=14", completeTrailingMath("Lecture notes\n2+3*4="))
        assertEquals("2×3=6", completeTrailingMath("2×3="))
        assertEquals("2+2=4\n", completeTrailingMath("2+2=\n"))
    }

    @Test
    fun previousAssignmentsFeedTrailingEquation() {
        assertEquals(
            "width=12\nheight=4\nwidth*height=48",
            completeTrailingMath("width=12\nheight=4\nwidth*height="),
        )
    }

    @Test
    fun undefinedVariablesDoNotInsertGuessedResults() {
        assertEquals("known=5\nknown+missing=", completeTrailingMath("known=5\nknown+missing="))
    }

    @Test
    fun invalidOrAlreadyCompletedTextStaysUnchanged() {
        assertEquals("Lecture notes", completeTrailingMath("Lecture notes"))
        assertEquals("2+=", completeTrailingMath("2+="))
        assertEquals("2+2=4", completeTrailingMath("2+2=4"))
    }
}
