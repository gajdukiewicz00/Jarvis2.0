package org.jarvis.desktop.features.voice

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jarvis.desktop.api.ApiClient

/**
 * Read model for the Voice command catalog ("ты можешь сказать…").
 *
 * Fetches the spoken-command catalog from `GET /api/v1/voice/help`. The shape
 * varies (flat list, grouped categories, or {command, description} objects), so
 * parsing handles each common form and groups into categories when possible.
 */
class VoiceHelpReadModel(
    private val apiClient: ApiClient
) {
    private val objectMapper = jacksonObjectMapper()

    data class Command(
        val phrase: String,
        val description: String
    )

    data class Category(
        val name: String,
        val commands: List<Command>
    )

    fun helpCatalog(): List<Category> {
        val response = apiClient.get("/voice/help")
        return parse(objectMapper.readTree(response))
    }

    private fun parse(root: JsonNode): List<Category> {
        // Grouped form: { "categories": [ { name, commands: [...] } ] }
        val categoriesNode = when {
            root.path("categories").isArray -> root.path("categories")
            else -> null
        }
        if (categoriesNode != null) {
            return categoriesNode.map { node ->
                Category(
                    name = node.path("name").asText("Commands"),
                    commands = parseCommandArray(node.path("commands"))
                )
            }.filter { it.commands.isNotEmpty() }
        }

        // Object-of-categories form: { "audio": [...], "memory": [...] }
        if (root.isObject && root.fields().asSequence().any { it.value.isArray }) {
            val categories = mutableListOf<Category>()
            root.fields().forEach { (name, value) ->
                if (value.isArray) {
                    val commands = parseCommandArray(value)
                    if (commands.isNotEmpty()) {
                        categories += Category(name, commands)
                    }
                }
            }
            if (categories.isNotEmpty()) return categories
        }

        // Flat array form: [ {command, description} ] or ["phrase", ...]
        val flat = when {
            root.isArray -> parseCommandArray(root)
            root.path("commands").isArray -> parseCommandArray(root.path("commands"))
            root.path("help").isArray -> parseCommandArray(root.path("help"))
            else -> emptyList()
        }
        return if (flat.isEmpty()) emptyList() else listOf(Category("Commands", flat))
    }

    private fun parseCommandArray(node: JsonNode): List<Command> {
        if (!node.isArray) return emptyList()
        return node.mapNotNull { element ->
            when {
                element.isTextual -> Command(element.asText(), "")
                element.isObject -> {
                    val phrase = firstNonBlank(
                        element.path("command").textOrNull(),
                        element.path("phrase").textOrNull(),
                        element.path("example").textOrNull(),
                        element.path("text").textOrNull(),
                        element.path("name").textOrNull()
                    )
                    val description = firstNonBlank(
                        element.path("description").textOrNull(),
                        element.path("detail").textOrNull(),
                        element.path("help").textOrNull()
                    ) ?: ""
                    phrase?.let { Command(it, description) }
                }
                else -> null
            }
        }
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    private fun JsonNode.textOrNull(): String? =
        if (isMissingNode || isNull) null else asText(null)?.takeIf { it.isNotBlank() }
}
