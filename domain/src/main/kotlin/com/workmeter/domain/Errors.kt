package com.workmeter.domain

sealed class BusinessError(message: String) : RuntimeException(message)
class InvalidInput(message: String) : BusinessError(message)
class NotFound : BusinessError("Recurso no encontrado")
class Conflict(message: String) : BusinessError(message)
class Unauthorized : BusinessError("Sesión o credenciales no válidas")
fun requireInput(condition: Boolean, message: String) {
    if (!condition) throw InvalidInput(message)
}
