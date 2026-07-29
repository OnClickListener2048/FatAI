package ai.fatai.feature.tools

import ai.fatai.chat.ProviderType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Provider schema adapters. Add a new provider here without changing a [Tool] implementation. */
object DefaultToolProviderAdapters {
    fun all(): List<ToolProviderAdapter> = listOf(
        OpenAICompatibleToolAdapter,
        GeminiToolAdapter,
        AnthropicToolAdapter
    )
}

object OpenAICompatibleToolAdapter : ToolProviderAdapter {
    override val providers = ProviderType.entries.filter(ProviderType::isOpenAICompatible).toSet()

    override fun encode(definitions: List<ToolDefinition>): ProviderToolPayload = ProviderToolPayload(
        fieldName = "tools",
        value = buildJsonArray {
            definitions.forEach { definition ->
                add(buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", definition.name)
                        put("description", definition.description)
                        put("parameters", jsonSchema(definition, typeName = "object"))
                    })
                })
            }
        }
    )

    override fun decode(call: ProviderToolCall): ToolCall = ToolCall(call.name, call.arguments)
}

object GeminiToolAdapter : ToolProviderAdapter {
    override val providers = setOf(ProviderType.Gemini)

    override fun encode(definitions: List<ToolDefinition>): ProviderToolPayload = ProviderToolPayload(
        fieldName = "tools",
        value = buildJsonArray {
            add(buildJsonObject {
                put("functionDeclarations", buildJsonArray {
                    definitions.forEach { definition ->
                        add(buildJsonObject {
                            put("name", definition.name)
                            put("description", definition.description)
                            put("parameters", jsonSchema(definition, typeName = "OBJECT"))
                        })
                    }
                })
            })
        }
    )

    override fun decode(call: ProviderToolCall): ToolCall = ToolCall(call.name, call.arguments)
}

object AnthropicToolAdapter : ToolProviderAdapter {
    override val providers = setOf(ProviderType.Claude)

    override fun encode(definitions: List<ToolDefinition>): ProviderToolPayload = ProviderToolPayload(
        fieldName = "tools",
        value = buildJsonArray {
            definitions.forEach { definition ->
                add(buildJsonObject {
                    put("name", definition.name)
                    put("description", definition.description)
                    put("input_schema", jsonSchema(definition, typeName = "object"))
                })
            }
        }
    )

    override fun decode(call: ProviderToolCall): ToolCall = ToolCall(call.name, call.arguments)
}

private fun jsonSchema(definition: ToolDefinition, typeName: String): JsonObject = buildJsonObject {
    put("type", typeName)
    put("properties", buildJsonObject {
        definition.parameters.forEach { parameter ->
            put(parameter.name, buildJsonObject {
                put("type", "string")
                put("description", parameter.description)
                if (parameter.allowedValues.isNotEmpty()) {
                    put("enum", JsonArray(parameter.allowedValues.sorted().map(::JsonPrimitive)))
                }
            })
        }
    })
    val required = definition.parameters.filter(ToolParameter::required).map(ToolParameter::name)
    if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
}
