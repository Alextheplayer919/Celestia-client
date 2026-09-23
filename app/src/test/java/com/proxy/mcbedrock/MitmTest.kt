package com.proxy.mcbedrock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs [MitmChecks] under JUnit. Unlike the inspection suite these checks need the
 * protocol and crypto libraries on the test classpath, which is why they live in
 * their own file: the inspection layer stays dependency-free and can still be run
 * from a bare command line during development.
 */
class MitmTest {

    @Test
    fun terminatedSessionCorePassesAllChecks() {
        val failures = MitmChecks.runAll(verbose = true)
        assertEquals("MITM checks failed (see output above)", 0, failures)
    }

    @Test
    fun suiteActuallyRanItsChecks() {
        MitmChecks.runAll(verbose = false)
        assertTrue(
            "expected a substantial number of checks, ran ${MitmChecks.lastPassed}",
            MitmChecks.lastPassed >= 40
        )
    }
}
