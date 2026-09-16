package net.portswigger.mcp.providers

import burp.api.montoya.logging.Logging
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Adds or updates a single entry inside the `mcpServers` object of an MCP client's JSON settings
 * file, such as Claude Desktop's `claude_desktop_config.json` or Gemini CLI's `settings.json`.
 *
 * The settings file belongs to the client, so unrelated settings and any other configured server
 * are carried over untouched. Clients refuse to start when their settings file cannot be parsed,
 * so the result is validated and moved into place rather than written directly, and an existing
 * file that cannot be understood is left exactly as it was.
 */
internal class McpClientSettingsWriter(private val logging: Logging) {

    /**
     * @param settingsFile the client's settings file; created along with its parent directory if missing
     * @param serverName   the key of the entry to create or replace within `mcpServers`
     * @param serverEntry  the entry to store for [serverName]
     * @param indent       the indentation the client itself uses when writing the file
     * @return the entry previously stored for [serverName], or null when it wasn't configured yet
     * @throws IllegalStateException if the existing settings file cannot be read, cannot be parsed or
     *                               doesn't have the expected shape. It is left untouched in that case.
     */
    fun upsertServer(
        settingsFile: Path,
        serverName: String,
        serverEntry: JsonObject,
        indent: String = DEFAULT_INDENT,
    ): JsonObject? {
        val settings = readSettings(settingsFile)

        val servers: MutableMap<String, JsonElement> = when (val existing = settings[MCP_SERVERS_KEY]) {
            null -> LinkedHashMap()
            is JsonObject -> LinkedHashMap(existing)
            else -> throw IllegalStateException(
                "Expected '$MCP_SERVERS_KEY' in $settingsFile to be a JSON object, but found a " +
                    "${existing.javaClass.simpleName}. Update the file and try again."
            )
        }

        val replaced = servers[serverName] as? JsonObject
        servers[serverName] = serverEntry

        val updated: MutableMap<String, JsonElement> = LinkedHashMap(settings)
        updated[MCP_SERVERS_KEY] = JsonObject(servers)

        val content = encode(JsonObject(updated), indent)

        try {
            writeAtomically(settingsFile, content)
        } catch (e: IOException) {
            throw IOException("Failed to write $settingsFile: ${e.message}", e)
        }

        logging.logToOutput("Added '$serverName' MCP server to $settingsFile")

        return replaced
    }

    private fun readSettings(settingsFile: Path): JsonObject {
        if (!settingsFile.exists()) {
            return JsonObject(emptyMap())
        }

        val content = try {
            settingsFile.readText()
        } catch (e: IOException) {
            throw IllegalStateException("Failed to read $settingsFile: ${e.message}")
        }

        if (content.isBlank()) {
            // Clients sometimes create an empty file before they have anything to store in it
            return JsonObject(emptyMap())
        }

        val parsed = try {
            Json.parseToJsonElement(content)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to parse $settingsFile: ${e.message}. The file was left unchanged. " +
                    "JSON comments and trailing commas aren't supported, so either remove them or add " +
                    "the Burp MCP server to the file manually."
            )
        }

        return parsed as? JsonObject ?: throw IllegalStateException(
            "Expected $settingsFile to contain a JSON object, but found a ${parsed.javaClass.simpleName}. " +
                "The file was left unchanged."
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun encode(settings: JsonObject, indent: String): String {
        val json = Json {
            prettyPrint = true
            prettyPrintIndent = indent
            encodeDefaults = true
        }

        val content = json.encodeToString(JsonObject.serializer(), settings)

        // Guard against persisting something the client wouldn't be able to load
        try {
            Json.parseToJsonElement(content)
        } catch (e: Exception) {
            throw IllegalStateException("Refusing to write settings that could not be re-parsed: ${e.message}")
        }

        return content
    }

    private fun writeAtomically(settingsFile: Path, content: String) {
        val directory = settingsFile.parent
        if (directory != null && !directory.exists()) {
            Files.createDirectories(directory)
        }

        val temporaryFile = Files.createTempFile(directory, "${settingsFile.fileName}-", ".tmp")
        try {
            Files.writeString(temporaryFile, content)
            Files.move(temporaryFile, settingsFile, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            try {
                Files.deleteIfExists(temporaryFile)
            } catch (ignored: IOException) {
                // Nothing useful to do if the half written file can't be cleaned up
            }
            throw e
        }
    }

    companion object {
        const val MCP_SERVERS_KEY = "mcpServers"
        private const val DEFAULT_INDENT = "    "
    }
}
