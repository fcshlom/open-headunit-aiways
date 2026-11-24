package com.andrerinas.openheadunit.aiways

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiwaysCarPowerTest {

    @Test
    fun `only the launcher's car-off holds reconnecting back`() {
        assertTrue(AiwaysCarPower.holdsOff(AiwaysCarPower.OFF_REASON))
        // other units' ACC intents and the inferred power loss keep their upstream behaviour
        assertFalse(AiwaysCarPower.holdsOff("com.fyt.boot.ACCOFF"))
        assertFalse(AiwaysCarPower.holdsOff("inferred: power lost beside a screen-off"))
        assertFalse(AiwaysCarPower.holdsOff(null))
    }
}
