package com.workmeter.application

import com.workmeter.domain.*
import java.time.Instant
import java.util.UUID

interface UnitOfWork { fun <T> execute(action: () -> T): T }
interface MemberRepository {
    fun findByEmail(email: String): Member?
    fun findMember(id: UUID): Member?
    fun addMember(member: Member)
    fun lockMember(id: UUID)
}
interface SessionRepository {
    fun addSession(session: Session)
    fun findSession(id: UUID): Session?
    fun deleteSession(id: UUID)
    fun deleteExpiredSessions(now: Instant)
}
interface ProjectRepository {
    fun listProjects(memberId: UUID): List<Project>
    fun findProject(memberId: UUID, id: UUID): Project?
    fun saveProject(project: Project): Project
}
data class EntryQuery(
    val from: Instant, val to: Instant, val projectId: UUID? = null,
    val orphans: Boolean = false, val offset: Int = 0, val limit: Int = 200
) {
    init {
        requireInput(from < to, "Rango de fechas no válido")
        requireInput(offset >= 0 && limit in 1..500, "Paginación no válida")
    }
}
interface TimeEntryRepository {
    fun listEntries(memberId: UUID, query: EntryQuery): List<TimeEntry>
    fun findEntry(memberId: UUID, id: UUID): TimeEntry?
    fun findExternal(memberId: UUID, externalId: String): TimeEntry?
    fun findOpen(memberId: UUID): TimeEntry?
    fun overlaps(memberId: UUID, interval: TimeInterval, exceptId: UUID?): Boolean
    fun saveEntry(entry: TimeEntry): TimeEntry
    fun deleteEntry(entry: TimeEntry)
}
interface PasswordHasher {
    fun hash(password: String): String
    fun matches(password: String, hash: String): Boolean
}
interface TokenIssuer { fun issue(session: Session): String }
