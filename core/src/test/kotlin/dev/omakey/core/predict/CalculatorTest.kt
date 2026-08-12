package dev.omakey.core.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalculatorTest {

    @Test
    fun `evaluates plain addition and subtraction`() {
        assertEquals(19.0, Calculator.evaluate("12+7"))
        assertEquals(5.0, Calculator.evaluate("12-7"))
    }

    @Test
    fun `evaluates multiplication and division`() {
        assertEquals(24.0, Calculator.evaluate("6*4"))
        assertEquals(3.0, Calculator.evaluate("12/4"))
    }

    @Test
    fun `respects standard operator precedence`() {
        // Not 20 (naive left-to-right) — * binds tighter than +.
        assertEquals(14.0, Calculator.evaluate("2+3*4"))
        assertEquals(10.0, Calculator.evaluate("2*3+4"))
    }

    @Test
    fun `handles decimals and negative numbers`() {
        assertEquals(4.5, Calculator.evaluate("2+2.5"))
        assertEquals(-5.0, Calculator.evaluate("-2-3"))
        assertEquals(1.0, Calculator.evaluate("3+-2"))
    }

    @Test
    fun `ignores whitespace`() {
        assertEquals(19.0, Calculator.evaluate("12 + 7"))
    }

    @Test
    fun `rejects a plain number with no operator`() {
        // Otherwise any number typed right before an unrelated "=" (e.g. a URL query string)
        // would spuriously "evaluate" to itself.
        assertNull(Calculator.evaluate("1234"))
    }

    @Test
    fun `rejects malformed or non-arithmetic input`() {
        assertNull(Calculator.evaluate(""))
        assertNull(Calculator.evaluate("12+"))
        assertNull(Calculator.evaluate("+"))
        assertNull(Calculator.evaluate("12+abc"))
        assertNull(Calculator.evaluate("1/0"))
    }

    @Test
    fun `formatResult drops trailing zero for whole numbers`() {
        assertEquals("19", Calculator.formatResult(19.0))
        assertEquals("4.5", Calculator.formatResult(4.5))
    }
}
