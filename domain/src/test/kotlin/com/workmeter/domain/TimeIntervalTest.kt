package com.workmeter.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class TimeIntervalTest {
    private val start = Instant.parse("2026-10-05T07:00:00Z")

    @Test fun `rejects an end before start`() {
        assertThrows(InvalidInput::class.java) { TimeInterval(start, start.minusSeconds(1)) }
    }

    @Test fun `adjacent intervals do not overlap`() {
        val first = TimeInterval(start, start.plusSeconds(3600))
        assertFalse(first.overlaps(TimeInterval(start.plusSeconds(3600), null)))
        assertTrue(first.overlaps(TimeInterval(start.plusSeconds(3599), null)))
    }

    @Test fun `an open interval reserves time from its start onwards`() {
        val open = TimeInterval(start, null)
        assertTrue(open.overlaps(TimeInterval(start.plusSeconds(1), start.plusSeconds(60))))
        assertNull(open.seconds())
    }

    @Test fun `duration follows instants across a daylight saving change`() {
        val interval = TimeInterval(Instant.parse("2026-10-25T00:30:00Z"), Instant.parse("2026-10-25T02:30:00Z"))
        assertEquals(7200L, interval.seconds())
    }
}
