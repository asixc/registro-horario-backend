package com.workmeter.infrastructure.config

import com.workmeter.application.*
import com.workmeter.domain.Member
import com.workmeter.infrastructure.auth.TokenCodec
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.Locale
import java.util.Optional
import java.util.UUID

@ApplicationScoped
class Bootstrap(
    private val members: MemberRepository, private val hasher: PasswordHasher, private val tx: UnitOfWork,
    private val tokens: TokenCodec,
    @ConfigProperty(name = "workmeter.bootstrap.email") private val email: Optional<String>,
    @ConfigProperty(name = "workmeter.bootstrap.password") private val password: Optional<String>,
    @ConfigProperty(name = "workmeter.bootstrap.name") private val name: String
) {
    fun start(@Observes event: StartupEvent) {
        tokens.validateConfiguration()
        if (email.isEmpty && password.isEmpty) return
        val normalized = email.orElse("").trim().lowercase(Locale.ROOT)
        require(normalized.contains("@") && normalized.length <= 254 && name.length in 1..200) { "Perfil inicial no válido" }
        val secret = password.orElse("")
        require(secret.length >= 12 && secret.toByteArray().size <= 72) { "La contraseña inicial debe tener al menos 12 caracteres y como máximo 72 bytes" }
        tx.execute {
            if (members.findByEmail(normalized) == null)
                members.addMember(Member(UUID.randomUUID(), normalized, name, hasher.hash(secret)))
        }
    }
}
