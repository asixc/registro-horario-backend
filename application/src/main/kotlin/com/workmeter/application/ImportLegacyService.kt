package com.workmeter.application

import com.workmeter.domain.*
import java.time.Clock
import java.util.UUID

data class LegacyCandidate(val index: Int, val externalId: String?, val input: EntryInput?,
    val externalProjectId: String?, val raw: String, val error: String? = null)
data class ImportRow(val index: Int, val externalId: String?, val status: String,
    val message: String?, val projectId: UUID?, val externalProjectId: String?)
data class ImportReport(val dryRun: Boolean, val rows: List<ImportRow>) {
    val ready: Int get() = rows.count { it.status == "READY" }
    val imported: Int get() = rows.count { it.status == "IMPORTED" }
    val skipped: Int get() = rows.count { it.status == "SKIPPED" }
    val conflicts: Int get() = rows.count { it.status == "CONFLICT" }
}
class ImportLegacyService(
    private val members: MemberRepository, private val projects: ProjectRepository,
    private val entries: TimeEntryRepository, private val tx: UnitOfWork, private val clock: Clock
) {
    fun import(memberId: UUID, candidates: List<LegacyCandidate>, mappings: Map<String, UUID>, dryRun: Boolean): ImportReport =
        tx.execute {
            requireInput(candidates.size <= 10000, "Máximo 10000 registros por lote")
            members.lockMember(memberId)
            mappings.values.forEach { projects.findProject(memberId, it) ?: throw NotFound() }
            val projectMap = projects.listProjects(memberId).filter { it.externalId != null }
                .associate { it.externalId!! to it.id } + mappings
            val accepted = mutableListOf<TimeEntry>()
            val seen = mutableSetOf<String>()
            val rows = candidates.sortedWith(compareBy<LegacyCandidate> { it.input?.start }.thenBy { it.index }).map { candidate ->
                process(memberId, candidate, projectMap, dryRun, accepted, seen)
            }
            ImportReport(dryRun, rows.sortedBy { it.index })
        }

    private fun process(memberId: UUID, candidate: LegacyCandidate, projectMap: Map<String, UUID>,
        dryRun: Boolean, accepted: MutableList<TimeEntry>, seen: MutableSet<String>): ImportRow {
        val projectId = candidate.externalProjectId?.let { projectMap[it] }
        fun row(status: String, message: String? = null) =
            ImportRow(candidate.index, candidate.externalId, status, message, projectId, candidate.externalProjectId)
        if (candidate.error != null || candidate.input == null || candidate.externalId.isNullOrBlank())
            return row("CONFLICT", candidate.error ?: "Falta ID externo o intervalo")
        if (!seen.add(candidate.externalId)) return row("CONFLICT", "ID externo repetido en el lote")
        val input = candidate.input
        val interval = TimeInterval(input.start, input.end)
        val existing = entries.findExternal(memberId, candidate.externalId)
        if (existing != null) {
            val originalMatches = existing.interval == interval && existing.projectId == projectId &&
                existing.kind == input.kind && existing.description == input.description &&
                existing.legacyPayload == candidate.raw
            return if (originalMatches) row("SKIPPED") else row("CONFLICT", "El ID externo existe con datos diferentes; no se sobrescribe")
        }
        if (entries.overlaps(memberId, interval, null) || accepted.any { it.interval.overlaps(interval) })
            return row("CONFLICT", "Intervalo solapado")
        if (interval.end == null && (entries.findOpen(memberId) != null || accepted.any { it.interval.end == null }))
            return row("CONFLICT", "Ya existe un intervalo abierto")
        val now = clock.instant()
        val entry = TimeEntry(UUID.randomUUID(), memberId, projectId, interval, input.kind, input.description,
            EntrySource.LEGACY_IMPORT, candidate.externalId, now, now, legacyPayload = candidate.raw)
        accepted.add(entry)
        if (!dryRun) entries.saveEntry(entry)
        return row(if (dryRun) "READY" else "IMPORTED", if (candidate.externalProjectId != null && projectId == null)
            "Proyecto externo conservado en el original; pendiente de clasificación" else null)
    }
}
