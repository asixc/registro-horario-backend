package com.workmeter.infrastructure.auth

import com.workmeter.application.PasswordHasher
import io.quarkus.elytron.security.common.BcryptUtil
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class BcryptHasher : PasswordHasher {
    override fun hash(password: String): String = BcryptUtil.bcryptHash(password)
    override fun matches(password: String, hash: String): Boolean = BcryptUtil.matches(password, hash)
}
