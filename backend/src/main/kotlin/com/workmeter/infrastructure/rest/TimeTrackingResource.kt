package com.workmeter.infrastructure.rest

import com.workmeter.application.*
import com.workmeter.domain.*
import com.workmeter.infrastructure.auth.SessionPrincipal
import jakarta.ws.rs.*
import jakarta.ws.rs.core.*
import java.time.Instant
import java.util.UUID

data class ProjectRequest(val name: String, val context: ProjectContext = ProjectContext.EMPLOYMENT, val externalId: String? = null)
data class CreateEntryRequest(val start: Instant, val end: Instant? = null, val projectId: UUID? = null,
    val kind: EntryKind = EntryKind.WORK, val description: String? = null) {
    fun input() = EntryInput(start, end, projectId, kind, description)
}
data class UpdateEntryRequest(val start: Instant, val end: Instant?, val projectId: UUID?,
    val version: Long, val kind: EntryKind = EntryKind.WORK, val description: String? = null) {
    fun input() = EntryInput(start, end, projectId, kind, description)
}
data class CloseEntryRequest(val end: Instant, val version: Long)
data class EntryResponse(val id: UUID, val projectId: UUID?, val start: Instant, val end: Instant?,
    val kind: EntryKind, val description: String?, val source: EntrySource, val externalId: String?,
    val version: Long, val createdAt: Instant, val updatedAt: Instant)
fun TimeEntry.response() = EntryResponse(id, projectId, interval.start, interval.end, kind, description,
    source, externalId, version, createdAt, updatedAt)

@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
class TimeTrackingResource(private val tracking: TimeTrackingService) {
    @Context lateinit var security: SecurityContext
    private fun memberId() = (security.userPrincipal as SessionPrincipal).memberId

    @GET @Path("/projects") fun projects() = tracking.projects(memberId())
    @POST @Path("/projects") fun project(input: ProjectRequest): Response =
        Response.status(201).entity(tracking.createProject(memberId(), input.name, input.context, input.externalId)).build()

    @GET @Path("/time-entries")
    fun entries(@QueryParam("from") from: Instant, @QueryParam("to") to: Instant,
        @QueryParam("projectId") projectId: UUID?, @QueryParam("orphans") @DefaultValue("false") orphans: Boolean,
        @QueryParam("offset") @DefaultValue("0") offset: Int, @QueryParam("limit") @DefaultValue("200") limit: Int
    ) = tracking.list(memberId(), EntryQuery(from, to, projectId, orphans, offset, limit)).map { it.response() }

    @GET @Path("/time-entries/open") fun open(): Response =
        tracking.open(memberId())?.let { Response.ok(it.response()).build() } ?: Response.noContent().build()

    @GET @Path("/time-entries/{id}") fun entry(@PathParam("id") id: UUID) = tracking.get(memberId(), id).response()
    @POST @Path("/time-entries") fun create(input: CreateEntryRequest): Response =
        Response.status(201).entity(tracking.create(memberId(), input.input()).response()).build()
    @POST @Path("/time-entries/start") fun start(input: CreateEntryRequest): Response {
        requireInput(input.end == null, "Un fichaje de inicio no puede tener fin")
        return Response.status(201).entity(tracking.create(memberId(), input.input(), EntrySource.CLOCK).response()).build()
    }
    @PATCH @Path("/time-entries/{id}") fun update(@PathParam("id") id: UUID, input: UpdateEntryRequest) =
        tracking.update(memberId(), id, input.input(), input.version).response()
    @POST @Path("/time-entries/{id}/close") fun close(@PathParam("id") id: UUID, input: CloseEntryRequest) =
        tracking.close(memberId(), id, input.end, input.version).response()
    @DELETE @Path("/time-entries/{id}") fun delete(@PathParam("id") id: UUID, @QueryParam("version") version: Long): Response {
        tracking.delete(memberId(), id, version)
        return Response.noContent().build()
    }
}
