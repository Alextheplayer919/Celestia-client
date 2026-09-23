package com.proxy.mcbedrock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs [InspectionChecks] under JUnit so `gradle testDebugUnitTest` (and CI)
 * executes the same suite that can also be run straight from the command line
 * during development.
 */
class InspectionTest {

    @Test
    fun inspectionLayerPassesAllChecks() {
        val failures = InspectionChecks.runAll(verbose = true)
        assertEquals("inspection checks failed (see output above)", 0, failures)
    }

    @Test
    fun suiteActuallyRanItsChecks() {
        InspectionChecks.runAll(verbose = false)
        assertTrue(
            "expected the suite to execute a substantial number of checks, ran ${InspectionChecks.lastPassed}",
            InspectionChecks.lastPassed >= 90
        )
    }
}
