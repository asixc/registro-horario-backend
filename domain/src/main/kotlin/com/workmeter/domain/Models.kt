package com.workmeter.domain

import java.time.Instant
import java.util.UUID

data class Member(val id: UUID, val email: String, val displayName: String, val passwordHash: String)
data class Session(val id: UUID, val memberId: UUID, val expiresAt: Instant)
enum class ProjectContext { EMPLOYMENT, FREELANCE, PERSONAL }
data class Project(
    val id: UUID, val memberId: UUID, val name: String,
    val context: ProjectContext, val externalId: String? = null, val archived: Boolean = false
) {
    init { requireInput(name.isNotBlank() && name.length <= 200, "Nombre de proyecto no válido") }
}
enum class EntryKind { WORK, PERSONAL }
enum class EntrySource { MANUAL, CLOCK, LEGACY_IMPORT }
data class TimeEntry(
    val id: UUID, val memberId: UUID, val projectId: UUID?, val interval: TimeInterval,
    val kind: EntryKind, val description: String?, val source: EntrySource,
    val externalId: String?, val createdAt: Instant, val updatedAt: Instant,
    val version: Long = 0, val legacyPayload: String? = null
) {
    init { requireInput(description == null || description.length <= 4000, "Descripción demasiado larga") }
    fun close(at: Instant, now: Instant): TimeEntry {
        if (interval.end != null) throw Conflict("El intervalo ya está cerrado")
        return copy(interval = TimeInterval(interval.start, at), updatedAt = now)
    }
}
