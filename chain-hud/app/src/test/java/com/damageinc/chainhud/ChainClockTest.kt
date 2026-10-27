package com.damageinc.chainhud

import org.junit.Assert.*
import org.junit.Test

class ChainClockTest {
    @Test fun countdownUsesMonotonicClock() {
        val s = ChainSnapshot(1247, 157, 10_000L)
        assertEquals(157, s.remainingSeconds(10_000L))
        assertEquals(156, s.remainingSeconds(11_000L))
        assertEquals(0, s.remainingSeconds(170_000L))
    }
    @Test fun warnsAtThreshold() {
        val s = ChainSnapshot(123, 29, 10_000L)
        assertTrue(ChainClock.render(s, 10_000L).detail.contains("RISK"))
    }
    @Test fun staleDoesNotPretendToKnowDeadline() {
        val s = ChainSnapshot(500, 100, 0L)
        assertEquals("--:--", ChainClock.render(s, 46_000L).timer)
    }
    @Test fun absentChainDoesNotInventDeadline() {
        assertEquals("NO ACTIVE CHAIN", ChainClock.render(ChainSnapshot(0,0,0), 1_000L).detail)
    }
    @Test fun warningIsMonochromeText() {
        val s = ChainSnapshot(50, 15, 1000L)
        assertTrue(ChainClock.render(s, 1000L).detail.contains("HIT NOW"))
    }
}
