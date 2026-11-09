package com.workmeter.infrastructure.migration

import com.fasterxml.jackson.databind.JsonNode
import com.workmeter.application.EntryInput
import com.workmeter.application.LegacyCandidate
import com.workmeter.domain.*
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

@ApplicationScoped
class LegacyDecoder {
    fun decode(node: JsonNode, index: Int): LegacyCandidate {
        val externalId = node.get("id")?.takeIf { it.isTextual }?.asText()
        val projectId = node.get("projectId")?.takeUnless { it.isNull }?.asText()
        return try {
            requireInput(node.isObject && !externalId.isNullOrBlank() && externalId.length <= 200, "ID externo obligatorio")
            requireInput(projectId == null || projectId.length <= 200, "ID de proyecto externo no válido")
            val start = aliases(node, "inicio", "start") ?: throw InvalidInput("Falta la fecha de inicio")
            val end = aliases(node, "fin", "end")
            TimeInterval(start, end)
            val description = node.get("description")?.takeUnless { it.isNull }?.asText()
            requireInput(description == null || description.length <= 4000, "Descripción demasiado larga")
            LegacyCandidate(index, externalId, EntryInput(start, end, null, description = description), projectId, node.toString())
        } catch (exception: Exception) {
            LegacyCandidate(index, externalId, null, projectId, node.toString(),
                if (exception is InvalidInput) exception.message else "Fecha o formato no válido")
        }
    }

    private fun aliases(node: JsonNode, first: String, second: String): Instant? {
        val a = node.get(first)
        val b = node.get(second)
        val left = a?.let { instant(it) }
        val right = b?.let { instant(it) }
        requireInput(a == null || b == null || left == right, "Conflicto entre $first y $second")
        return if (a != null) left else right
    }

    private fun instant(node: JsonNode): Instant? {
        if (node.isNull) return null
        if (node.isTextual) return Instant.parse(node.asText())
        val seconds = node.get("seconds") ?: node.get("_seconds")
        val nanos = node.get("nanoseconds") ?: node.get("_nanoseconds")
        requireInput(node.isObject && seconds?.isIntegralNumber == true, "Timestamp de Firestore no válido")
        requireInput(nanos == null || nanos.isIntegralNumber && nanos.asLong() in 0..999999999, "Nanosegundos no válidos")
        return Instant.ofEpochSecond(seconds!!.asLong(), nanos?.asLong() ?: 0)
    }
}
