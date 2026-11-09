package com.workmeter

import com.fasterxml.jackson.databind.ObjectMapper
import com.workmeter.infrastructure.migration.LegacyDecoder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LegacyDecoderTest {
    private val mapper = ObjectMapper()
    private fun decode(json: String) = LegacyDecoder().decode(mapper.readTree(json), 0)
    @Test fun `does not choose silently between conflicting dates`() {
        val result = decode("""{"id":"one","inicio":"2024-05-01T08:00:00Z","start":"2024-05-01T09:00:00Z"}""")
        assertNotNull(result.error)
    }
    @Test fun `null and closed aliases are a conflict`() {
        assertNotNull(decode("""{"id":"one","start":"2024-05-01T08:00:00Z","fin":null,"end":"2024-05-01T09:00:00Z"}""").error)
    }
    @Test fun `preserves open timestamp and original team metadata`() {
        val result = decode("""{"id":"one","inicio":{"seconds":1714550400,"nanoseconds":0},"fin":null,"teamId":"legacy-team"}""")
        assertNull(result.error)
        assertNull(result.input!!.end)
        assertTrue(result.raw.contains("legacy-team"))
    }
    @Test fun `invalid negative duration becomes a reported conflict`() {
        assertNotNull(decode("""{"id":"one","start":"2024-05-01T09:00:00Z","end":"2024-05-01T08:00:00Z"}""").error)
    }
}
