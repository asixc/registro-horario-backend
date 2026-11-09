package com.workmeter.infrastructure.config

import com.workmeter.application.*
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import java.time.Clock

@ApplicationScoped
class Wiring {
    @Produces @Singleton fun clock(): Clock = Clock.systemUTC()
    @Produces @Singleton fun auth(members: MemberRepository, sessions: SessionRepository,
        hasher: PasswordHasher, tokens: TokenIssuer, transactions: UnitOfWork, clock: Clock) =
        AuthService(members, sessions, hasher, tokens, transactions, clock)
    @Produces @Singleton fun tracking(members: MemberRepository, projects: ProjectRepository,
        entries: TimeEntryRepository, transactions: UnitOfWork, clock: Clock) =
        TimeTrackingService(members, projects, entries, transactions, clock)
    @Produces @Singleton fun importer(members: MemberRepository, projects: ProjectRepository,
        entries: TimeEntryRepository, transactions: UnitOfWork, clock: Clock) =
        ImportLegacyService(members, projects, entries, transactions, clock)
}
