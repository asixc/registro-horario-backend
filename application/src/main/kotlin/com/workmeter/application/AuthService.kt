package com.workmeter.application

import com.workmeter.domain.*
import java.time.Clock
import java.time.Duration
import java.util.Locale
import java.util.UUID

data class LoginResult(val member: Member, val session: Session, val token: String)

class AuthService(
    private val members: MemberRepository, private val sessions: SessionRepository,
    private val hasher: PasswordHasher, private val tokens: TokenIssuer,
    private val transactions: UnitOfWork, private val clock: Clock
) {
    private val dummyHash by lazy { hasher.hash(UUID.randomUUID().toString()) }
    fun login(email: String, password: String): LoginResult = transactions.execute {
        requireInput(email.length <= 254 && password.toByteArray().size <= 72, "Credenciales demasiado largas")
        val member = members.findByEmail(email.trim().lowercase(Locale.ROOT))
        val valid = hasher.matches(password, member?.passwordHash ?: dummyHash)
        if (!valid || member == null) throw Unauthorized()
        val now = clock.instant()
        sessions.deleteExpiredSessions(now)
        val session = Session(UUID.randomUUID(), member.id, now.plus(Duration.ofHours(8)))
        sessions.addSession(session)
        LoginResult(member, session, tokens.issue(session))
    }
    fun authenticate(memberId: UUID, sessionId: UUID): Member = transactions.execute {
        val session = sessions.findSession(sessionId) ?: throw Unauthorized()
        if (session.memberId != memberId || !session.expiresAt.isAfter(clock.instant())) throw Unauthorized()
        members.findMember(memberId) ?: throw Unauthorized()
    }
    fun logout(sessionId: UUID) = transactions.execute { sessions.deleteSession(sessionId) }
}
