package io.github.jackwener.dsh.core.capability

import io.github.jackwener.dsh.core.model.Risk
import io.github.jackwener.dsh.core.model.ToolGroup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add

@Serializable
data class ToolDescriptor(
    val name: String,
    val group: String,
    val description: String,
    val inputSchema: JsonObject,
    val risk: Risk,
    val needsUnlock: Boolean,
)

sealed interface Content {
    data class Text(val text: String) : Content
    data class Image(val mimeType: String, val base64: String) : Content
}

class ToolResult(val ok: Boolean, val content: List<Content>, val errorCode: String? = null, val errorMessage: String? = null) {
    companion object {
        fun text(text: String) = ToolResult(true, listOf(Content.Text(text)))
        fun image(mime: String, b64: String, caption: String? = null) =
            ToolResult(true, listOfNotNull(caption?.let { Content.Text(it) }, Content.Image(mime, b64)))
        fun error(code: String, message: String) = ToolResult(false, listOf(Content.Text(message)), code, message)
    }
}

/** Thrown by tools when an Android permission or service is missing. */
open class PermissionMissingException(val what: String) : Exception(what)
class InvalidArgsException(message: String) : Exception(message)

data class CallContext(val sessionId: String, val callId: String, val unattended: Boolean)

abstract class Tool(
    val name: String,
    val group: ToolGroup,
    val risk: Risk,
    val needsUnlock: Boolean,
    val description: String,
    schema: SchemaBuilder.() -> Unit,
    /** Shizuku dependency; CapabilityHub appends the live availability to the description. */
    val shell: ShellUse = ShellUse.None,
    /** What happens without Shizuku (model-facing, e.g. "open the Wi-Fi panel for the user to tap"). */
    val shellFallback: String? = null,
) {
    val descriptor = ToolDescriptor(name, group.id, description, SchemaBuilder().apply(schema).build(), risk, needsUnlock)
    abstract suspend fun invoke(args: JsonObject, call: CallContext): ToolResult
}

/** Small JSON-Schema DSL for tool inputs. */
class SchemaBuilder {
    private val props = LinkedHashMap<String, JsonObject>()
    private val required = mutableListOf<String>()

    private fun add(name: String, required: Boolean, o: JsonObject) { props[name] = o; if (required) this.required += name }
    fun string(name: String, desc: String, required: Boolean = false, enum: List<String>? = null) = add(name, required, buildJsonObject {
        put("type", "string"); put("description", desc)
        if (enum != null) putJsonArray("enum") { enum.forEach { add(it) } }
    })
    fun int(name: String, desc: String, required: Boolean = false) = add(name, required, buildJsonObject { put("type", "integer"); put("description", desc) })
    fun number(name: String, desc: String, required: Boolean = false) = add(name, required, buildJsonObject { put("type", "number"); put("description", desc) })
    fun bool(name: String, desc: String, required: Boolean = false) = add(name, required, buildJsonObject { put("type", "boolean"); put("description", desc) })
    fun strings(name: String, desc: String, required: Boolean = false) = add(name, required, buildJsonObject {
        put("type", "array"); put("description", desc); putJsonObject("items") { put("type", "string") }
    })
    fun obj(name: String, desc: String, required: Boolean = false) = add(name, required, buildJsonObject { put("type", "object"); put("description", desc) })
    fun raw(name: String, schema: JsonObject, required: Boolean = false) = add(name, required, schema)

    fun build() = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        putJsonArray("required") { required.forEach { add(it) } }
        put("additionalProperties", false)
    }
}

// ---- argument helpers ---------------------------------------------------------------------------

fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
fun JsonObject.reqStr(k: String): String = str(k)?.takeIf { it.isNotEmpty() } ?: throw InvalidArgsException("missing '$k'")
fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull
fun JsonObject.double(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
fun JsonObject.strs(k: String): List<String> = (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
fun JsonElement?.asList(): List<JsonElement> = (this as? JsonArray)?.jsonArray ?: emptyList()
