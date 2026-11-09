package cn.com.omnimind.assists.controller.http

import kotlinx.serialization.json.*

/** Declare inherited required properties in composition branches without changing the tool catalog. */
object GeminiToolSchema {
    fun project(schema: JsonObject): JsonObject = visit(schema, emptyMap())

    private val references = setOf("\$ref", "\$dynamicRef", "\$recursiveRef", "\$id", "id", "\$anchor", "\$dynamicAnchor", "\$recursiveAnchor")
    private val compositions = setOf("allOf", "anyOf", "oneOf")
    private val maps = setOf("properties", "patternProperties", "\$defs", "definitions", "dependentSchemas")
    private val children = setOf("items", "additionalProperties", "unevaluatedProperties", "contains", "not", "if", "then", "else", "propertyNames", "additionalItems", "unevaluatedItems")

    private fun safeToCopy(value: JsonElement): Boolean = when (value) {
        is JsonObject -> value.keys.none(references::contains) && value.values.all(::safeToCopy)
        is JsonArray -> value.all(::safeToCopy)
        else -> true
    }

    private fun visit(schema: JsonObject, inherited: Map<String, JsonElement>): JsonObject {
        val closed = listOf("additionalProperties", "unevaluatedProperties").any {
            schema.containsKey(it) && schema[it] != JsonPrimitive(true)
        }
        val available = if (closed || schema.keys.any(references::contains)) emptyMap() else inherited
        val properties = (schema["properties"] as? JsonObject).orEmpty().toMutableMap()
        (schema["required"] as? JsonArray).orEmpty().forEach { field ->
            val key = (field as? JsonPrimitive)?.contentOrNull ?: return@forEach
            available[key]?.takeIf { key !in properties && safeToCopy(it) }?.let { properties[key] = it }
        }
        val expanded = schema.toMutableMap()
        if (properties.isNotEmpty()) expanded["properties"] = JsonObject(properties)
        val visible = available + properties
        return JsonObject(expanded.mapValues { (key, value) ->
            when {
                key in compositions && value is JsonArray -> JsonArray(value.map { branch ->
                    if (branch is JsonObject) visit(branch, visible) else branch
                })
                key in maps && value is JsonObject -> JsonObject(value.mapValues { (_, child) ->
                    if (child is JsonObject) visit(child, emptyMap()) else child
                })
                key in children && value is JsonObject -> visit(value, emptyMap())
                key in setOf("items", "prefixItems") && value is JsonArray -> JsonArray(value.map {
                    if (it is JsonObject) visit(it, emptyMap()) else it
                })
                else -> value
            }
        })
    }
}
