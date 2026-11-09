package com.workmeter

import com.fasterxml.jackson.databind.ObjectMapper
import com.workmeter.application.MemberRepository
import com.workmeter.application.PasswordHasher
import com.workmeter.application.UnitOfWork
import com.workmeter.domain.Member
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.response.Response
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture

@QuarkusTest
class ApiTest {
    @Inject lateinit var tx: UnitOfWork
    @Inject lateinit var em: EntityManager
    @Inject lateinit var members: MemberRepository
    @Inject lateinit var hasher: PasswordHasher
    @Inject lateinit var mapper: ObjectMapper
    private lateinit var cookie: String

    @BeforeEach fun prepare() {
        tx.execute {
            em.createQuery("delete from TimeEntryEntity").executeUpdate()
            em.createQuery("delete from ProjectEntity").executeUpdate()
            em.createQuery("delete from SessionEntity").executeUpdate()
        }
        cookie = login()
    }
    private fun login(email: String = "test@example.com", password: String = "Testing-only-1234"): String =
        given().contentType("application/json").header("X-WorkMeter-Client", "web")
            .body(mapOf("email" to email, "password" to password))
            .post("/api/v1/auth/login").then().statusCode(200).extract().cookie("workmeter_session")

    private fun request(session: String = cookie) = given().contentType("application/json")
        .header("X-WorkMeter-Client", "web").cookie("workmeter_session", session)

    private fun create(start: String, end: String?, project: String? = null): Response =
        request().body(mapOf("start" to start, "end" to end, "projectId" to project)).post("/api/v1/time-entries")

    @Test fun `login rejects invalid credentials and logout revokes captured cookie`() {
        given().contentType("application/json").header("X-WorkMeter-Client", "web")
            .body(mapOf("email" to "test@example.com", "password" to "wrong"))
            .post("/api/v1/auth/login").then().statusCode(401)
        given().get("/api/v1/projects").then().statusCode(401)
        request().get("/api/v1/me").then().statusCode(200).body("email", equalTo("test@example.com"))
        request().post("/api/v1/auth/logout").then().statusCode(204)
        request().get("/api/v1/me").then().statusCode(401)
    }

    @Test fun `csrf protection is required even with a cookie`() {
        given().cookie("workmeter_session", cookie).contentType("application/json")
            .body(mapOf("name" to "Project")).post("/api/v1/projects").then().statusCode(403)
    }

    @Test fun `crud preserves versions and reports concurrent edits`() {
        val project = request().body(mapOf("name" to "Work", "externalId" to "legacy-project"))
            .post("/api/v1/projects").then().statusCode(201).extract().path<String>("id")
        val entry = create("2026-10-05T07:00:00Z", "2026-10-05T11:00:00Z", project)
            .then().statusCode(201).extract().response()
        val id = entry.path<String>("id")
        val version = entry.path<Int>("version")
        val update = mapOf("start" to "2026-10-05T07:00:00Z", "end" to "2026-10-05T12:00:00Z",
            "projectId" to project, "version" to version, "description" to "Revised")
        val edited = request().body(update).patch("/api/v1/time-entries/$id").then().statusCode(200).extract().response()
        request().body(update).patch("/api/v1/time-entries/$id").then().statusCode(409)
        request().queryParam("from", "2026-10-05T00:00:00Z").queryParam("to", "2026-10-06T00:00:00Z")
            .get("/api/v1/time-entries").then().statusCode(200).body("size()", equalTo(1))
        request().queryParam("version", edited.path<Int>("version")).delete("/api/v1/time-entries/$id").then().statusCode(204)
        request().get("/api/v1/time-entries/$id").then().statusCode(404)
    }

    @Test fun `invalid dates overlap and duplicate open entries are rejected`() {
        create("2026-10-05T09:00:00Z", "2026-10-05T08:00:00Z").then().statusCode(400)
        create("2026-10-05T07:00:00Z", "2026-10-05T08:00:00Z").then().statusCode(201)
        create("2026-10-05T07:30:00Z", "2026-10-05T08:30:00Z").then().statusCode(409)
        val open = create("2026-10-05T08:00:00Z", null).then().statusCode(201).extract().response()
        create("2026-10-05T09:00:00Z", null).then().statusCode(409)
        request().get("/api/v1/time-entries/open").then().statusCode(200).body("id", equalTo(open.path<String>("id")))
        request().body(mapOf("end" to "2026-10-05T09:00:00Z", "version" to open.path<Int>("version")))
            .post("/api/v1/time-entries/${open.path<String>("id")}/close").then().statusCode(200)
        request().get("/api/v1/time-entries/open").then().statusCode(204)
    }

    @Test fun `cross member access and project assignment are forbidden`() {
        tx.execute {
            if (members.findByEmail("other@example.com") == null)
                members.addMember(Member(UUID.randomUUID(), "other@example.com", "Other", hasher.hash("Testing-only-1234")))
        }
        val other = login("other@example.com")
        val project = request(other).body(mapOf("name" to "Other project")).post("/api/v1/projects")
            .then().statusCode(201).extract().path<String>("id")
        create("2026-10-05T07:00:00Z", null, project).then().statusCode(404)
        val id = create("2026-10-05T07:00:00Z", null).then().statusCode(201).extract().path<String>("id")
        request(other).get("/api/v1/time-entries/$id").then().statusCode(404)
    }

    @Test fun `concurrent clock starts allow exactly one interval`() {
        val results = (1..2).map {
            CompletableFuture.supplyAsync { create("2026-10-05T07:00:00Z", null).statusCode }
        }.map { it.join() }.sorted()
        Assertions.assertEquals(listOf(201, 409), results)
    }

    @Test fun `legacy import defaults to dry run and is idempotent with conflicts visible`() {
        val records = listOf(
            mapOf("id" to "one", "start" to "2024-05-01T08:00:00Z", "end" to "2024-05-01T09:00:00Z"),
            mapOf("id" to "bad", "start" to "2024-05-01T10:00:00Z", "end" to "2024-05-01T09:00:00Z"))
        request().body(mapOf("records" to records)).post("/api/v1/admin/import-legacy")
            .then().statusCode(200).body("ready", equalTo(1)).body("conflicts", equalTo(1))
        tx.execute { Assertions.assertEquals(0L, em.createQuery("select count(e) from TimeEntryEntity e", Long::class.javaObjectType).singleResult) }
        request().body(mapOf("records" to records, "dryRun" to false)).post("/api/v1/admin/import-legacy")
            .then().statusCode(200).body("imported", equalTo(1)).body("conflicts", equalTo(1))
        request().body(mapOf("records" to records, "dryRun" to false)).post("/api/v1/admin/import-legacy")
            .then().statusCode(200).body("imported", equalTo(0)).body("skipped", equalTo(1))
    }

    @Test fun `checked in historical corpus is accounted for without modifying the source`() {
        val path = Path.of("../../registro-horario/backup10.05.2024.json")
        Assumptions.assumeTrue(Files.exists(path), "Historical backup is optional outside this workspace")
        val records = mapper.readTree(Files.readString(path))
        val report = request().body(mapOf("records" to records)).post("/api/v1/admin/import-legacy")
            .then().statusCode(200).extract().response()
        Assertions.assertEquals(443, report.path<List<Any>>("rows").size)
        Assertions.assertTrue(report.path<Int>("conflicts") >= 1)
        tx.execute { Assertions.assertEquals(0L, em.createQuery("select count(e) from TimeEntryEntity e", Long::class.javaObjectType).singleResult) }
    }
}
