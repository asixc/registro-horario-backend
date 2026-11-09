package com.workmeter.infrastructure.rest

import com.workmeter.domain.*
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

data class ApiError(val code: String, val message: String)

@Provider
class BusinessErrorMapper : ExceptionMapper<BusinessError> {
    override fun toResponse(error: BusinessError): Response {
        val status = when(error) {
            is InvalidInput -> 400
            is Unauthorized -> 401
            is NotFound -> 404
            is Conflict -> 409
        }
        return Response.status(status).entity(ApiError(error.javaClass.simpleName, error.message ?: "Error"))
            .header("Cache-Control", "no-store").build()
    }
}
