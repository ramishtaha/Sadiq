package com.ramish.saduq.core

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The core module is a plain JVM module on purpose: it must build and test in seconds, with no
 * Android runtime. This test proves the toolchain and the JVM target are sane.
 */
class BuildSanityTest {

    @Test
    fun jvmToolchainRuns() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun javaTimeIsAvailableForScheduleMath() {
        assertEquals(DayOfWeek.THURSDAY, LocalDate.of(2026, 10, 29).dayOfWeek)
    }
}
