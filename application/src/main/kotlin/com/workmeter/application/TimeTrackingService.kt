package com.workmeter.application

import com.workmeter.domain.*
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class EntryInput(
    val start: Instant, val end: Instant?, val projectId: UUID?,
    val kind: EntryKind = EntryKind.WORK, val description: String? = null
)

class TimeTrackingService(
    private val members: MemberRepository, private val projects: ProjectRepository,
    private val entries: TimeEntryRepository, private val transactions: UnitOfWork,
    private val clock: Clock
) {
    fun projects(memberId: UUID): List<Project> = transactions.execute { projects.listProjects(memberId) }
    fun createProject(memberId: UUID, name: String, context: ProjectContext, externalId: String?): Project =
        transactions.execute {
            members.lockMember(memberId)
            requireInput(externalId == null || externalId.isNotBlank() && externalId.length <= 200, "ID externo no válido")
            if (externalId != null && projects.listProjects(memberId).any { it.externalId == externalId })
                throw Conflict("El proyecto externo ya existe")
            projects.saveProject(Project(UUID.randomUUID(), memberId, name.trim(), context, externalId))
        }

    fun list(memberId: UUID, query: EntryQuery): List<TimeEntry> =
        transactions.execute { entries.listEntries(memberId, query) }
    fun get(memberId: UUID, id: UUID): TimeEntry =
        transactions.execute { entries.findEntry(memberId, id) ?: throw NotFound() }
    fun open(memberId: UUID): TimeEntry? = transactions.execute { entries.findOpen(memberId) }

    fun create(memberId: UUID, input: EntryInput, source: EntrySource = EntrySource.MANUAL): TimeEntry =
        transactions.execute {
            members.lockMember(memberId)
            validateProject(memberId, input.projectId)
            val interval = TimeInterval(input.start, input.end)
            checkAvailability(memberId, interval, null)
            val now = clock.instant()
            entries.saveEntry(TimeEntry(UUID.randomUUID(), memberId, input.projectId, interval,
                input.kind, input.description, source, null, now, now))
        }

    fun update(memberId: UUID, id: UUID, input: EntryInput, expectedVersion: Long): TimeEntry =
        transactions.execute {
            members.lockMember(memberId)
            val entry = current(memberId, id, expectedVersion)
            validateProject(memberId, input.projectId)
            val interval = TimeInterval(input.start, input.end)
            checkAvailability(memberId, interval, id)
            entries.saveEntry(entry.copy(projectId = input.projectId, interval = interval,
                kind = input.kind, description = input.description, updatedAt = clock.instant()))
        }

    fun close(memberId: UUID, id: UUID, end: Instant, expectedVersion: Long): TimeEntry =
        transactions.execute {
            members.lockMember(memberId)
            val entry = current(memberId, id, expectedVersion).close(end, clock.instant())
            checkAvailability(memberId, entry.interval, id)
            entries.saveEntry(entry)
        }

    fun delete(memberId: UUID, id: UUID, expectedVersion: Long) = transactions.execute {
        members.lockMember(memberId)
        entries.deleteEntry(current(memberId, id, expectedVersion))
    }

    private fun current(memberId: UUID, id: UUID, version: Long): TimeEntry {
        val entry = entries.findEntry(memberId, id) ?: throw NotFound()
        if (entry.version != version) throw Conflict("El registro ha cambiado; recarga antes de editar")
        return entry
    }
    private fun validateProject(memberId: UUID, projectId: UUID?) {
        if (projectId == null) return
        val project = projects.findProject(memberId, projectId) ?: throw NotFound()
        if (project.archived) throw Conflict("El proyecto está archivado")
    }
    private fun checkAvailability(memberId: UUID, interval: TimeInterval, exceptId: UUID?) {
        if (interval.end == null && entries.findOpen(memberId)?.let { it.id != exceptId } == true)
            throw Conflict("Ya hay un intervalo abierto")
        if (entries.overlaps(memberId, interval, exceptId)) throw Conflict("El intervalo se solapa con otro registro")
    }
}
