package net.portswigger.mcp.providers

import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.*
import net.portswigger.mcp.config.McpConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class GeminiCliProviderTest {

    private val logging = mockk<Logging>(relaxed = true)
    private val proxyJarManager = mockk<ProxyJarManager>(relaxed = true)

    @Test
    fun `creates the settings file when the client has none`(@TempDir home: Path) {
        val proxyJar = stubProxyJar(home)

        val result = provider(home).install(config())

        val file = settingsFile(home)
        assertTrue(Files.exists(file), "Expected $file to be created")

        val burp = file.json().server(GeminiCliProvider.SERVER_NAME)
        val command = burp["command"]!!.jsonPrimitive.content.replace('\\', '/')
        assertTrue(command.endsWith("/bin/java"), "Expected the packaged java launcher, got $command")
        assertEquals(
            listOf("-jar", proxyJar.toString(), "--sse-url", "http://127.0.0.1:9876"),
            burp["args"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        assertFalse(burp.containsKey("trust"), "The server must not be trusted without the user asking for it")
        assertTrue(result.contains("gemini mcp list"), "Expected the result to mention how to verify: $result")
    }

    @Test
    fun `formats the file the way Gemini CLI does`(@TempDir home: Path) {
        stubProxyJar(home)

        provider(home).install(config())

        val content = settingsFile(home).readText()
        assertTrue(content.startsWith("{\n  \"mcpServers\""), "Expected two space indentation in:\n$content")
        assertTrue(content.contains("\n    \"burp\": {"), "Expected the entry to be nested, got:\n$content")
    }

    @Test
    fun `keeps unrelated settings and servers`(@TempDir home: Path) {
        stubProxyJar(home)
        writeSettings(
            home,
            """
            {
              "selectedAuthType": "oauth-personal",
              "mcpServers": {
                "context7": { "url": "https://mcp.context7.com/sse" }
              },
              "general": { "vimMode": true }
            }
            """.trimIndent()
        )

        provider(home).install(config("localhost", 1234))

        val settings = settingsFile(home).json()
        assertEquals("oauth-personal", settings["selectedAuthType"]!!.jsonPrimitive.content)
        assertTrue(
            settings["general"]!!.jsonObject["vimMode"]!!.jsonPrimitive.content.toBoolean(),
            "Existing nested settings should be kept"
        )

        val servers = settings["mcpServers"]!!.jsonObject
        assertTrue(servers.containsKey("context7"), "Other servers must be kept: $servers")
        assertTrue(servers.containsKey(GeminiCliProvider.SERVER_NAME), "Burp must be added: $servers")
        assertEquals(
            listOf("selectedAuthType", "mcpServers", "general"),
            settings.keys.toList(),
            "Existing keys should keep their position"
        )
    }

    @Test
    fun `replaces a previous entry instead of merging into it`(@TempDir home: Path) {
        stubProxyJar(home)
        writeSettings(home, """{ "mcpServers": { "burp": { "url": "http://localhost:1/sse" } } }""")

        provider(home).install(config())

        val burp = settingsFile(home).json().server(GeminiCliProvider.SERVER_NAME)
        assertTrue(burp.containsKey("command"), "Expected a stdio entry, got: $burp")
        assertFalse(burp.containsKey("url"), "The stale url key should be gone: $burp")
    }

    @Test
    fun `installing twice leaves the file unchanged`(@TempDir home: Path) {
        stubProxyJar(home)

        provider(home).install(config())
        val installed = settingsFile(home).readText()
        provider(home).install(config())

        assertEquals(installed, settingsFile(home).readText(), "Reinstalling should be idempotent")
    }

    @Test
    fun `leaves a settings file it cannot parse untouched`(@TempDir home: Path) {
        stubProxyJar(home)
        val original = """
            {
              // comments are valid for Gemini CLI, but we can't preserve them
              "mcpServers": {}
            }
        """.trimIndent()
        writeSettings(home, original)

        assertThrows(IllegalStateException::class.java) { provider(home).install(config()) }

        assertEquals(original, settingsFile(home).readText(), "The user's file must not be modified")
    }

    @Test
    fun `refuses to write into a mcpServers value that is not an object`(@TempDir home: Path) {
        stubProxyJar(home)
        val original = """{"mcpServers": []}"""
        writeSettings(home, original)

        assertThrows(IllegalStateException::class.java) { provider(home).install(config()) }

        assertEquals(original, settingsFile(home).readText())
    }

    @Test
    fun `uses the windows launcher when running on windows`(@TempDir home: Path) {
        stubProxyJar(home)

        provider(home, osName = "Windows 11", javaHome = "C:\\Program Files\\BurpSuitePro\\jre").install(config())

        val command = settingsFile(home).json().server(GeminiCliProvider.SERVER_NAME)["command"]!!.jsonPrimitive.content
        assertTrue(command.endsWith("java.exe"), "Expected a java.exe launcher, got $command")
    }

    @Test
    fun `reports the server url the client can actually connect to`() {
        assertEquals("http://127.0.0.1:9876", mcpServerUrl(config("0.0.0.0", 9876)))
        assertEquals("http://127.0.0.1:9876", mcpServerUrl(config("*", 9876)))
        assertEquals("http://localhost:9876", mcpServerUrl(config("localhost", 9876)))
        assertEquals("http://[::1]:1234", mcpServerUrl(config("[::1]", 1234)))
    }

    @Test
    fun `points at the file gemini mcp add uses`(@TempDir home: Path) {
        assertEquals(
            home.resolve(".gemini").resolve("settings.json"),
            provider(home).settingsFilePath(),
            "Gemini CLI reads its MCP servers from the user settings file"
        )
    }

    private fun provider(home: Path, osName: String = "Linux", javaHome: String = "/opt/burp/jre") = GeminiCliProvider(
        logging, proxyJarManager, home, osName, javaHome
    )

    private fun stubProxyJar(home: Path): Path {
        val proxyJar = home.resolve("mcp-proxy-all.jar")
        every { proxyJarManager.getProxyJar() } returns proxyJar
        return proxyJar
    }

    private fun settingsFile(home: Path): Path = home.resolve(".gemini").resolve("settings.json")

    private fun writeSettings(home: Path, content: String) {
        val file = settingsFile(home)
        file.parent.createDirectories()
        file.writeText(content)
    }

    private fun config(host: String = "127.0.0.1", port: Int = 9876): McpConfig {
        val storage = mockk<PersistedObject>()
        every { storage.getBoolean(any()) } returns true
        every { storage.getString(any()) } returns host
        every { storage.getInteger(any()) } returns port
        every { storage.setBoolean(any(), any()) } returns Unit
        every { storage.setString(any(), any()) } returns Unit
        every { storage.setInteger(any(), any()) } returns Unit

        return McpConfig(storage, logging)
    }

    private fun Path.json(): JsonObject = Json.parseToJsonElement(readText()).jsonObject

    private fun JsonObject.server(name: String): JsonObject = this["mcpServers"]!!.jsonObject[name]!!.jsonObject
}
