package com.workmeter.domain

import java.time.Duration
import java.time.Instant

data class TimeInterval(val start: Instant, val end: Instant?) {
    init { requireInput(end == null || !end.isBefore(start), "El fin no puede ser anterior al inicio") }
    fun seconds(): Long? = end?.let { Duration.between(start, it).seconds }
    fun overlaps(other: TimeInterval): Boolean {
        if (end == start || other.end == other.start) return false
        return (end == null || other.start < end) && (other.end == null || start < other.end)
    }
}
