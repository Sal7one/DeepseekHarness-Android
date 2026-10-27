package io.github.jackwener.dsh.core.capability.tools

import io.github.jackwener.dsh.core.capability.CallContext
import io.github.jackwener.dsh.core.capability.InvalidArgsException
import io.github.jackwener.dsh.core.capability.Tool
import io.github.jackwener.dsh.core.capability.ToolResult
import io.github.jackwener.dsh.core.capability.ToolSet
import io.github.jackwener.dsh.core.capability.bool
import io.github.jackwener.dsh.core.capability.long
import io.github.jackwener.dsh.core.data.RuleDao
import io.github.jackwener.dsh.core.engine.RpcJson
import io.github.jackwener.dsh.core.model.Risk
import io.github.jackwener.dsh.core.model.RuleSpec
import io.github.jackwener.dsh.core.model.ToolGroup
import io.github.jackwener.dsh.core.trigger.TriggerHub
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class RuleTools @Inject constructor(
    private val rules: RuleDao,
    private val hub: Provider<TriggerHub>,
) : ToolSet {
    override val tools = listOf(
        object : Tool("rule_create", ToolGroup.Rules, Risk.write, false, RULE_DOC, {
            obj("rule", "RuleSpec JSON as documented", required = true)
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                if (call.unattended) throw InvalidArgsException("Automations cannot create automations")
                val spec = runCatching { RpcJson.decodeFromJsonElement(RuleSpec.serializer(), args["rule"] ?: error("missing rule")) }
                    .getOrElse { throw InvalidArgsException("Invalid rule: ${it.message}") }
                val id = hub.get().create(spec)
                return ToolResult.text("Automation #$id \"${spec.name}\" created and enabled. Allowed tools: ${spec.allowedTools}")
            }
        },
        object : Tool("rule_list", ToolGroup.Rules, Risk.read, false, "List automations with their specs and status.", {}) {
            override suspend fun invoke(args: JsonObject, call: CallContext) = ToolResult.text(rules.all().joinToString("\n") {
                "#${it.id} ${if (it.enabled) "on" else "off"} last=${it.lastFiredAt ?: "-"} ${it.specJson}"
            }.ifEmpty { "No automations." })
        },
        object : Tool("rule_update", ToolGroup.Rules, Risk.write, false, "Enable/disable an automation, or replace its spec.", {
            int("id", "Automation id", required = true); bool("enabled", "On/off"); obj("rule", "New RuleSpec (optional)")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val id = args.long("id") ?: throw InvalidArgsException("id")
                val e = rules.get(id) ?: throw InvalidArgsException("No automation #$id")
                args["rule"]?.let { r ->
                    val spec = RpcJson.decodeFromJsonElement(RuleSpec.serializer(), r)
                    rules.upsert(e.copy(specJson = RpcJson.encodeToString(RuleSpec.serializer(), spec)))
                }
                args.bool("enabled")?.let { hub.get().setEnabled(id, it) } ?: hub.get().sync()
                return ToolResult.text("Updated #$id")
            }
        },
        object : Tool("rule_delete", ToolGroup.Rules, Risk.write, false, "Delete an automation.", {
            int("id", "Automation id", required = true)
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                hub.get().delete(args.long("id") ?: throw InvalidArgsException("id")); return ToolResult.text("Deleted")
            }
        },
    )

    private companion object {
        val RULE_DOC = """
Create an automation that runs when something happens on the phone, even while locked. Confirm the details with the user first.
RuleSpec: {"name": str, "trigger": T, "conditions": [C...], "action": A, "allowedTools": [tool names the unattended run may use]}
T (pick one, field "type"):
 {"type":"time","at":"HH:mm","days":[1..7 Mon=1]} | {"type":"time","everyMinutes":n>=15} | {"type":"time","onceAtEpochMs":ms}
 {"type":"wifi","connected":bool,"ssid":opt} | {"type":"bluetooth","connected":bool,"deviceName":opt}
 {"type":"power","connected":bool} | {"type":"battery_low","percent":n} | {"type":"headset","plugged":bool}
 {"type":"screen","event":"on|off|unlock"} | {"type":"notification","packageName":opt,"keyword":opt}
 {"type":"app_opened","packageName":str} | {"type":"geofence","lat":d,"lng":d,"radiusM":200,"enter":bool}
C: {"type":"time_range","from":"HH:mm","to":"HH:mm"} | {"type":"days","days":[..]} | {"type":"battery","min":n,"max":n} | {"type":"charging","charging":bool}
A: {"type":"steps","steps":[{"tool":"sound_mode","args":{"mode":"silent"}}]}  (fixed tool calls, no model, preferred when possible)
 | {"type":"agent","prompt":"what the agent should do"}  (an unattended agent run; it can only use allowedTools and cannot ask the user)
Tools needing an unlocked screen will wait for the user to unlock; prefer tools that work while locked.""".trim()
    }
}
