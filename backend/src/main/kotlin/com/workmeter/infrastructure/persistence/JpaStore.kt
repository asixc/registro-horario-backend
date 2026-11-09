package com.workmeter.infrastructure.persistence

import com.workmeter.application.*
import com.workmeter.domain.*
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class JpaStore(private val em: EntityManager) : MemberRepository, SessionRepository, ProjectRepository, TimeEntryRepository {
    override fun findByEmail(email: String): Member? =
        em.createQuery("from MemberEntity where email = :email", MemberEntity::class.java)
            .setParameter("email", email).resultList.firstOrNull()?.toDomain()
    override fun findMember(id: UUID): Member? = em.find(MemberEntity::class.java, id)?.toDomain()
    override fun lockMember(id: UUID) {
        em.find(MemberEntity::class.java, id, LockModeType.PESSIMISTIC_WRITE) ?: throw NotFound()
    }
    override fun addMember(member: Member) {
        em.persist(MemberEntity().apply {
            id = member.id; email = member.email; displayName = member.displayName; passwordHash = member.passwordHash
        })
    }
    override fun addSession(session: Session) {
        em.persist(SessionEntity().apply { id = session.id; memberId = session.memberId; expiresAt = session.expiresAt })
    }
    override fun findSession(id: UUID): Session? = em.find(SessionEntity::class.java, id)?.let { Session(it.id, it.memberId, it.expiresAt) }
    override fun deleteSession(id: UUID) {
        em.find(SessionEntity::class.java, id)?.let { em.remove(it) }
    }
    override fun deleteExpiredSessions(now: Instant) {
        em.createQuery("delete from SessionEntity where expiresAt <= :now").setParameter("now", now).executeUpdate()
    }
    override fun listProjects(memberId: UUID): List<Project> =
        em.createQuery("from ProjectEntity where memberId = :owner order by name, id", ProjectEntity::class.java)
            .setParameter("owner", memberId).resultList.map { it.toDomain() }
    override fun findProject(memberId: UUID, id: UUID): Project? =
        em.find(ProjectEntity::class.java, id)?.takeIf { it.memberId == memberId }?.toDomain()
    override fun saveProject(project: Project): Project {
        em.persist(ProjectEntity().apply {
            id = project.id; memberId = project.memberId; name = project.name
            context = project.context.name; externalId = project.externalId; archived = project.archived
        })
        em.flush()
        return project
    }
    override fun listEntries(memberId: UUID, query: EntryQuery): List<TimeEntry> {
        val conditions = buildString {
            append("memberId = :owner and start < :to and (end is null or end > :from or (end = start and start >= :from))")
            if (query.projectId != null) append(" and projectId = :project")
            if (query.orphans) append(" and projectId is null")
        }
        val hql = em.createQuery("from TimeEntryEntity where $conditions order by start desc, id", TimeEntryEntity::class.java)
            .setParameter("owner", memberId).setParameter("from", query.from).setParameter("to", query.to)
        if (query.projectId != null) hql.setParameter("project", query.projectId)
        return hql.setFirstResult(query.offset).setMaxResults(query.limit).resultList.map { it.toDomain() }
    }
    override fun findEntry(memberId: UUID, id: UUID): TimeEntry? =
        em.find(TimeEntryEntity::class.java, id)?.takeIf { it.memberId == memberId }?.toDomain()
    override fun findExternal(memberId: UUID, externalId: String): TimeEntry? =
        em.createQuery("from TimeEntryEntity where memberId = :owner and externalId = :external", TimeEntryEntity::class.java)
            .setParameter("owner", memberId).setParameter("external", externalId).resultList.firstOrNull()?.toDomain()
    override fun findOpen(memberId: UUID): TimeEntry? =
        em.createQuery("from TimeEntryEntity where memberId = :owner and end is null", TimeEntryEntity::class.java)
            .setParameter("owner", memberId).resultList.firstOrNull()?.toDomain()
    override fun overlaps(memberId: UUID, interval: TimeInterval, exceptId: UUID?): Boolean {
        if (interval.end == interval.start) return false
        val sql = buildString {
            append("memberId = :owner and (end is null or end > :start) and (end is null or end <> start)")
            if (interval.end != null) append(" and start < :end")
            if (exceptId != null) append(" and id <> :exceptId")
        }
        val query = em.createQuery("select count(e) from TimeEntryEntity e where $sql", Long::class.javaObjectType)
            .setParameter("owner", memberId).setParameter("start", interval.start)
        if (interval.end != null) query.setParameter("end", interval.end)
        if (exceptId != null) query.setParameter("exceptId", exceptId)
        return query.singleResult > 0
    }
    override fun saveEntry(entry: TimeEntry): TimeEntry {
        val existing = em.find(TimeEntryEntity::class.java, entry.id)
        val entity = existing ?: TimeEntryEntity().apply {
            id = entry.id; memberId = entry.memberId; createdAt = entry.createdAt
        }
        entity.projectId = entry.projectId; entity.start = entry.interval.start; entity.end = entry.interval.end
        entity.kind = entry.kind.name; entity.description = entry.description; entity.source = entry.source.name
        entity.externalId = entry.externalId; entity.updatedAt = entry.updatedAt; entity.legacyPayload = entry.legacyPayload
        if (existing == null) em.persist(entity)
        em.flush()
        return entity.toDomain()
    }
    override fun deleteEntry(entry: TimeEntry) {
        em.remove(em.find(TimeEntryEntity::class.java, entry.id))
        em.flush()
    }
}
private fun MemberEntity.toDomain() = Member(id, email, displayName, passwordHash)
private fun ProjectEntity.toDomain() = Project(id, memberId, name, ProjectContext.valueOf(context), externalId, archived)
private fun TimeEntryEntity.toDomain() = TimeEntry(id, memberId, projectId, TimeInterval(start, end),
    EntryKind.valueOf(kind), description, EntrySource.valueOf(source), externalId, createdAt, updatedAt, version, legacyPayload)
