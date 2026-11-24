package com.andrerinas.openheadunit.aiways

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiwaysServerHintTest {

    private val limit = AiwaysServerHint.FAILURES_BEFORE_HINT
    private val repeat = AiwaysServerHint.REPEAT_MS

    @Test
    fun `no hint before the third silent attempt in a row`() {
        assertFalse(AiwaysServerHint.due(limit - 1, false, 0L, 1_000L))
        assertTrue(AiwaysServerHint.due(limit, false, 0L, 1_000L))
    }

    @Test
    fun `while it lasts the hint comes back every five minutes, not on every attempt`() {
        assertFalse(AiwaysServerHint.due(limit + 1, true, 1_000L, 1_000L + repeat - 1))
        assertTrue(AiwaysServerHint.due(limit + 4, true, 1_000L, 1_000L + repeat))
    }
}
