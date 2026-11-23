package tech.fonia.lessons

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * The golden vectors for iOS parity: spec/content/vectors/plans-v2.json holds every request and its plan. This test
 * rebuilds each plan and compares it; `-Pfonia.writeVectors=true` rewrites the file after an intended change.
 */
class VectorsTest {
    private val file = File(Fixtures.vectorsDir, "plans-v2.json")
    private val compact = Json { prettyPrint = false }

    @Test
    fun `plans match the golden vectors`() {
        val content = Fixtures.content
        val builder = Fixtures.builder
        if (System.getProperty("fonia.writeVectors") == "true" || !file.exists()) {
            val lines = Vectors.requests(content).map { (name, request) ->
                val o = JsonObject(mapOf(
                    "name" to kotlinx.serialization.json.JsonPrimitive(name),
                    "request" to Vectors.requestJson(request),
                    "plan" to Vectors.planJson(builder.build(request)),
                ))
                compact.encodeToString(JsonObject.serializer(), o)
            }
            file.parentFile.mkdirs()
            file.writeText(buildString {
                append("{\"schema\":1,\"contentVersion\":\"").append(content.contentVersion).append("\",")
                append("\"seedFormula\":\"seed ^ sessionsCompleted*0x9E3779B97F4A7C15 ^ fnv1a64(sound/rung)\",\"vectors\":[\n")
                append(lines.joinToString(",\n"))
                append("\n]}\n")
            }, Charsets.UTF_8)
        }
        val root = Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
        assertEquals(content.contentVersion, root.getValue("contentVersion").jsonPrimitive.content,
            "vectors were made from another content version; rewrite them with -Pfonia.writeVectors=true")
        val vectors = root.getValue("vectors").jsonArray
        assertTrue(vectors.size >= 9 * 14 * 3, "only ${vectors.size} vectors")
        for (v in vectors) {
            val o = v.jsonObject
            val request = Vectors.requestFrom(o.getValue("request").jsonObject)
            assertEquals(o.getValue("request"), Vectors.requestJson(request), "request round trip ${o["name"]}")
            assertEquals(o.getValue("plan"), Vectors.planJson(builder.build(request)), "plan differs: ${o["name"]}")
        }
    }
}
