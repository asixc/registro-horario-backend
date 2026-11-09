package com.workmeter.infrastructure.auth

import com.workmeter.application.TokenIssuer
import com.workmeter.domain.Session
import com.workmeter.domain.Unauthorized
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jose4j.jws.AlgorithmIdentifiers
import org.jose4j.jws.JsonWebSignature
import org.jose4j.jwt.JwtClaims
import org.jose4j.jwt.NumericDate
import org.jose4j.jwt.consumer.JwtConsumerBuilder
import org.jose4j.jwa.AlgorithmConstraints
import org.jose4j.keys.HmacKey
import java.security.Principal
import java.util.Optional
import java.util.UUID

data class SessionPrincipal(val memberId: UUID, val sessionId: UUID) : Principal {
    override fun getName(): String = memberId.toString()
}

@ApplicationScoped
class TokenCodec(@ConfigProperty(name = "workmeter.jwt-secret") secret: Optional<String>) : TokenIssuer {
    private val key = HmacKey(secret.orElse("").toByteArray().also {
        require(it.size >= 32) { "WORKMETER_JWT_SECRET debe contener al menos 32 bytes" }
    })
    private val verifier = JwtConsumerBuilder().setRequireExpirationTime().setRequireSubject()
        .setExpectedIssuer("workmeter").setExpectedAudience("workmeter-web")
        .setVerificationKey(key)
        .setJwsAlgorithmConstraints(AlgorithmConstraints.ConstraintType.PERMIT, AlgorithmIdentifiers.HMAC_SHA256)
        .build()

    fun validateConfiguration() { require(key.encoded.size >= 32) }

    override fun issue(session: Session): String {
        val claims = JwtClaims().apply {
            issuer = "workmeter"; subject = session.memberId.toString()
            setAudience("workmeter-web"); jwtId = session.id.toString()
            setIssuedAtToNow(); expirationTime = NumericDate.fromSeconds(session.expiresAt.epochSecond)
        }
        return JsonWebSignature().apply {
            payload = claims.toJson(); algorithmHeaderValue = AlgorithmIdentifiers.HMAC_SHA256
            key = this@TokenCodec.key
        }.compactSerialization
    }

    fun verify(token: String): SessionPrincipal = try {
        val claims = verifier.processToClaims(token)
        SessionPrincipal(UUID.fromString(claims.subject), UUID.fromString(claims.jwtId))
    } catch (_: Exception) { throw Unauthorized() }
}
