package com.workmeter.infrastructure.rest

import com.workmeter.application.AuthService
import com.workmeter.infrastructure.auth.SessionPrincipal
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.*
import jakarta.ws.rs.core.*
import org.eclipse.microprofile.config.inject.ConfigProperty

data class LoginRequest(val email: String, val password: String)
data class MemberResponse(val id: String, val email: String, val displayName: String)

@ApplicationScoped
class LoginThrottle {
    private var window = 0L
    private var attempts = 0
    @Synchronized fun allow(): Boolean {
        val minute = System.nanoTime() / 60_000_000_000L
        if (minute != window) { window = minute; attempts = 0 }
        return ++attempts <= 30
    }
}

@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
class AuthResource(
    private val auth: AuthService, private val throttle: LoginThrottle,
    @ConfigProperty(name = "workmeter.cookie-secure") private val cookieSecure: Boolean
) {
    @Context lateinit var security: SecurityContext
    @POST @Path("/auth/login")
    fun login(input: LoginRequest): Response {
        if (!throttle.allow()) return Response.status(429).header("Retry-After", "60")
            .entity(ApiError("RateLimited", "Demasiados intentos; espera un minuto")).build()
        val result = auth.login(input.email, input.password)
        return Response.ok(MemberResponse(result.member.id.toString(), result.member.email, result.member.displayName))
            .cookie(cookie(result.token, 8 * 3600)).build()
    }
    @GET @Path("/me")
    fun me(): MemberResponse {
        val principal = security.userPrincipal as SessionPrincipal
        val member = auth.authenticate(principal.memberId, principal.sessionId)
        return MemberResponse(member.id.toString(), member.email, member.displayName)
    }
    @POST @Path("/auth/logout")
    fun logout(): Response {
        auth.logout((security.userPrincipal as SessionPrincipal).sessionId)
        return Response.noContent().cookie(cookie("", 0)).build()
    }
    private fun cookie(value: String, maxAge: Int): NewCookie =
        NewCookie.Builder("workmeter_session").value(value).path("/api").httpOnly(true)
            .secure(cookieSecure).sameSite(NewCookie.SameSite.STRICT).maxAge(maxAge).build()
}
