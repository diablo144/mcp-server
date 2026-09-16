package net.portswigger.mcp.providers

import burp.api.montoya.logging.Logging
import io.mockk.mockk
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class McpClientSettingsWriterTest {

    private val writer = McpClientSettingsWriter(mockk<Logging>(relaxed = true))
    private val entry = buildJsonObject {
        put("command", "java")
    }

    @Test
    fun `creates the file and any missing parent directory`(@TempDir root: Path) {
        val settingsFile = root.resolve("nested").resolve("dir").resolve("settings.json")

        writer.upsertServer(settingsFile, "burp", entry)

        assertTrue(Files.exists(settingsFile), "Expected $settingsFile to be created")
        val servers = parse(settingsFile)["mcpServers"]!!.jsonObject
        assertEquals("java", servers["burp"]!!.jsonObject["command"]!!.jsonPrimitive.content)
    }

    @Test
    fun `treats a blank file as having no settings yet`(@TempDir root: Path) {
        val settingsFile = root.resolve("settings.json")
        settingsFile.writeText("   ")

        writer.upsertServer(settingsFile, "burp", entry)

        assertEquals(1, parse(settingsFile).size, "Only the new server should be present")
    }

    @Test
    fun `returns the entry it replaced`(@TempDir root: Path) {
        val settingsFile = root.resolve("settings.json")
        settingsFile.parent.createDirectories()
        settingsFile.writeText("""{"mcpServers": {"burp": {"command": "old"}}}""")

        val replaced = writer.upsertServer(settingsFile, "burp", entry)

        assertEquals("old", replaced?.get("command")?.jsonPrimitive?.content)
        assertEquals("java", parse(settingsFile).server("burp")["command"]!!.jsonPrimitive.content)
    }

    @Test
    fun `returns null when the server was not configured yet`(@TempDir root: Path) {
        val settingsFile = root.resolve("settings.json")

        assertNull(writer.upsertServer(settingsFile, "burp", entry))
    }

    @Test
    fun `leaves the file untouched when it is not a JSON object`(@TempDir root: Path) {
        val settingsFile = root.resolve("settings.json")
        settingsFile.writeText("""["not", "settings"]""")

        assertThrows(IllegalStateException::class.java) {
            writer.upsertServer(settingsFile, "burp", entry)
        }

        assertEquals("""["not", "settings"]""", settingsFile.readText())
    }

    @Test
    fun `never leaves a partially written file behind`(@TempDir root: Path) {
        val settingsFile = root.resolve("settings.json")
        settingsFile.writeText("""{"mcpServers": {}}""")

        val temporaryFiles = { root.toFile().listFiles()?.filter { it.name.contains(".tmp") }.orEmpty() }

        writer.upsertServer(settingsFile, "burp", entry)

        assertTrue(temporaryFiles().isEmpty(), "Temporary files should not be left behind: ${temporaryFiles()}")
        assertTrue(Files.exists(settingsFile))
    }

    @Test
    fun `honours the requested indentation`(@TempDir root: Path) {
        val settingsFile = root.resolve("settings.json")

        writer.upsertServer(settingsFile, "burp", entry, indent = "  ")

        assertTrue(
            settingsFile.readText().startsWith("{\n  \"mcpServers\""),
            "Expected two space indentation in:\n${settingsFile.readText()}"
        )
    }

    private fun parse(settingsFile: Path): JsonObject = Json.parseToJsonElement(settingsFile.readText()).jsonObject

    private fun JsonObject.server(name: String): JsonObject = this["mcpServers"]!!.jsonObject[name]!!.jsonObject
}
