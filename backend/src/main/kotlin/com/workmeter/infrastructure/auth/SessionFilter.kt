package com.workmeter.infrastructure.auth

import com.workmeter.application.AuthService
import com.workmeter.domain.Unauthorized
import com.workmeter.infrastructure.rest.ApiError
import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.*
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.SecurityContext
import jakarta.ws.rs.ext.Provider
import java.security.Principal

@Provider @Priority(Priorities.AUTHENTICATION)
class SessionFilter(private val tokens: TokenCodec, private val auth: AuthService) : ContainerRequestFilter, ContainerResponseFilter {
    override fun filter(request: ContainerRequestContext) {
        if (!request.uriInfo.path.trimStart('/').startsWith("api/")) return
        if (request.method !in listOf("GET", "HEAD", "OPTIONS") &&
            request.getHeaderString("X-WorkMeter-Client") != "web") {
            request.abortWith(Response.status(403).entity(ApiError("Forbidden", "Falta la cabecera de protección CSRF")).build())
            return
        }
        if (request.uriInfo.path.trimStart('/') == "api/v1/auth/login") return
        try {
            val token = request.cookies["workmeter_session"]?.value ?: throw Unauthorized()
            val principal = tokens.verify(token)
            auth.authenticate(principal.memberId, principal.sessionId)
            val secure = request.securityContext.isSecure
            request.securityContext = object : SecurityContext {
                override fun getUserPrincipal(): Principal = principal
                override fun isUserInRole(role: String) = role == "member"
                override fun isSecure() = secure
                override fun getAuthenticationScheme() = "JWT"
            }
        } catch (_: Unauthorized) {
            request.abortWith(Response.status(401).entity(ApiError("Unauthorized", "Inicia sesión")).build())
        }
    }
    override fun filter(request: ContainerRequestContext, response: ContainerResponseContext) {
        if (request.uriInfo.path.trimStart('/').startsWith("api/")) {
            response.headers.putSingle("Cache-Control", "no-store")
            response.headers.putSingle("X-Content-Type-Options", "nosniff")
        }
    }
}
