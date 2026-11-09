package com.workmeter.infrastructure.persistence

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity @Table(name = "members")
class MemberEntity {
    @Id var id: UUID = UUID.randomUUID()
    lateinit var email: String
    @Column(name = "display_name") lateinit var displayName: String
    @Column(name = "password_hash") lateinit var passwordHash: String
}
@Entity @Table(name = "sessions")
class SessionEntity {
    @Id var id: UUID = UUID.randomUUID()
    @Column(name = "member_id") lateinit var memberId: UUID
    @Column(name = "expires_at") lateinit var expiresAt: Instant
}
@Entity @Table(name = "projects")
class ProjectEntity {
    @Id var id: UUID = UUID.randomUUID()
    @Column(name = "member_id") lateinit var memberId: UUID
    lateinit var name: String
    lateinit var context: String
    @Column(name = "external_id") var externalId: String? = null
    var archived: Boolean = false
}
@Entity @Table(name = "time_entries")
class TimeEntryEntity {
    @Id var id: UUID = UUID.randomUUID()
    @Column(name = "member_id") lateinit var memberId: UUID
    @Column(name = "project_id") var projectId: UUID? = null
    @Column(name = "start_at") lateinit var start: Instant
    @Column(name = "end_at") var end: Instant? = null
    lateinit var kind: String
    var description: String? = null
    lateinit var source: String
    @Column(name = "external_id") var externalId: String? = null
    @Column(name = "legacy_payload") var legacyPayload: String? = null
    @Column(name = "created_at") lateinit var createdAt: Instant
    @Column(name = "updated_at") lateinit var updatedAt: Instant
    @Version var version: Long = 0
}
