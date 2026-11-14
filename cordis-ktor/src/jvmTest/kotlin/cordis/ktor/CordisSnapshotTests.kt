package cordis.ktor

import cordis.kernel.Context
import cordis.kernel.MermaidVisitor
import cordis.kernel.TextTreeVisitor
import cordis.kernel.accept
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.charset
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CordisSnapshotTests {
    private suspend fun ApplicationTestBuilder.mountSnapshot(): Context {
        lateinit var context: Context
        application {
            install(Cordis)
            context = cordis
            routing { cordisSnapshot(context) }
        }
        startApplication()
        return context
    }

    private fun HttpResponse.assertPlainText() {
        assertEquals(HttpStatusCode.OK, status)
        assertEquals(ContentType.Text.Plain, contentType()?.withoutParameters())
        assertEquals(Charsets.UTF_8, contentType()?.charset())
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun treeMatchesTheTextTreeVisitor() =
        testApplication {
            val context = mountSnapshot()
            context.plugin(ParentPlugin(FeaturePlugin("Child")))

            val response = client.get("/snapshot/tree")

            response.assertPlainText()
            val expected = TextTreeVisitor().also { context.snapshot().accept(it) }.render()
            assertEquals(expected, response.bodyAsText())
            assertTrue("Child" in expected)
        }

    @Test
    fun mermaidHasOneNodePerPlugin() =
        testApplication {
            val context = mountSnapshot()
            context.plugin(ParentPlugin(FeaturePlugin("Child")))

            val response = client.get("/snapshot/mermaid")

            response.assertPlainText()
            val body = response.bodyAsText()
            assertTrue(body.startsWith("flowchart TD"))
            val nodes = body.lines().filter { Regex("""^\s*p\d+\[""").containsMatchIn(it) }
            assertEquals(context.snapshot().plugins.size, nodes.size)
            assertEquals(2, nodes.size)
            assertEquals(MermaidVisitor().also { context.snapshot().accept(it) }.render(), body)
        }

    @Test
    fun healthIsOkWhenEveryPluginIsActive() =
        testApplication {
            val context = mountSnapshot()
            context.plugin(ParentPlugin(FeaturePlugin("Child")))

            val response = client.get("/health")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
            val body = response.json()
            assertEquals(true, body.getValue("healthy").jsonPrimitive.boolean)
            listOf("failed", "missingServices", "pending").forEach { assertEquals(JsonArray(emptyList()), body.getValue(it), it) }
        }

    @Test
    fun healthIsUnavailableWithAFailedAndAPendingPlugin() =
        testApplication {
            val context = mountSnapshot()
            context.plugin(FailingPlugin)
            context.plugin(DependentPlugin)

            val response = client.get("/health")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
            val body = response.json()
            assertEquals(false, body.getValue("healthy").jsonPrimitive.boolean)

            val failed = body.items("failed").single()
            assertEquals("FailingPlugin", failed.string("name"))
            assertEquals("Failed", failed.string("status"))
            assertEquals(FAILURE_REASON, failed.string("reason"))
            assertEquals(JsonNull, failed.getValue("key"))

            for (list in listOf("missingServices", "pending")) {
                val item = body.items(list).single()
                assertEquals("DependentPlugin", item.string("name"), list)
                assertEquals("dependent", item.string("key"), list)
                assertEquals("Pending", item.string("status"), list)
                assertEquals(JsonNull, item.getValue("reason"), list)
                assertEquals(listOf("ProvidedKey"), item.getValue("missing").jsonArray.map { it.jsonPrimitive.content }, list)
            }
        }

    @Test
    fun emptyGraphAnswersEmptyTreeBareFlowchartAndHealthy() =
        testApplication {
            mountSnapshot()

            val tree = client.get("/snapshot/tree")
            tree.assertPlainText()
            assertEquals("", tree.bodyAsText())

            val mermaid = client.get("/snapshot/mermaid")
            mermaid.assertPlainText()
            assertEquals("flowchart TD", mermaid.bodyAsText())

            val health = client.get("/health")
            assertEquals(HttpStatusCode.OK, health.status)
            assertEquals(
                Json.parseToJsonElement("""{"healthy":true,"failed":[],"missingServices":[],"pending":[]}"""),
                Json.parseToJsonElement(health.bodyAsText()),
            )
        }

    private fun JsonObject.items(name: String): List<JsonObject> = getValue(name).jsonArray.map { it.jsonObject }

    private fun JsonObject.string(name: String): String? = getValue(name).jsonPrimitive.contentOrNull
}
