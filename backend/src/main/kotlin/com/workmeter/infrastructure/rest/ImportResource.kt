package com.workmeter.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.workmeter.application.ImportLegacyService
import com.workmeter.domain.requireInput
import com.workmeter.infrastructure.auth.SessionPrincipal
import com.workmeter.infrastructure.migration.LegacyDecoder
import jakarta.ws.rs.*
import jakarta.ws.rs.core.*
import java.util.UUID

data class ImportRequest(val records: List<JsonNode>, val projectMappings: Map<String, UUID> = emptyMap(), val dryRun: Boolean = true)

@Path("/api/v1/admin/import-legacy")
@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
class ImportResource(private val importer: ImportLegacyService, private val decoder: LegacyDecoder) {
    @Context lateinit var security: SecurityContext
    @POST fun import(input: ImportRequest): com.workmeter.application.ImportReport {
        requireInput(input.records.size <= 10000, "Máximo 10000 registros por lote")
        return importer.import((security.userPrincipal as SessionPrincipal).memberId,
            input.records.mapIndexed { index, node -> decoder.decode(node, index) }, input.projectMappings, input.dryRun)
    }
}
