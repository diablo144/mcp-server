package net.portswigger.mcp.providers

import burp.api.montoya.logging.Logging
import kotlinx.serialization.json.*
import net.portswigger.mcp.config.McpConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.swing.JFileChooser
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

interface Provider {
    val name: String
    val installButtonText: String
    val confirmationText: String?
    fun install(config: McpConfig): String?
}

class ClaudeDesktopProvider(
    private val logging: Logging,
    private val proxyJarManager: ProxyJarManager,
) : Provider {

    private val settingsWriter = McpClientSettingsWriter(logging)

    private val claudeConfigFileName = "claude_desktop_config.json"
    private val serverName = "burp"

    override val name = "Claude Desktop"
    override val installButtonText = "Install to $name"
    override val confirmationText =
        "Install to $name?\nThis will create an entry within $name's MCP configuration file ($claudeConfigFileName)"

    override fun install(config: McpConfig): String {
        val proxyJarFile = proxyJarManager.getProxyJar()

        val path = configFilePath() ?: error("Could not find Claude config path")

        val javaPath = javaExecutable()
        logging.logToOutput("Using Java from: $javaPath")

        val burpServerConfig = buildJsonObject {
            put("command", JsonPrimitive(javaPath))
            put("args", buildJsonArray {
                add(JsonPrimitive("-jar"))
                add(JsonPrimitive(proxyJarFile.toString()))
                add(JsonPrimitive("--sse-url"))
                add(JsonPrimitive(mcpServerUrl(config)))
            })
        }

        settingsWriter.upsertServer(path, serverName, burpServerConfig)

        logging.logToOutput("Installed Burp MCP Server to Claude Desktop config")

        return "Installation successful. Please restart $name if it is currently running."
    }

    private fun configFilePath(): Path? {
        val os = System.getProperty("os.name").lowercase()
        val home = System.getProperty("user.home")

        val candidatePaths = when {
            os.contains("win") -> windowsCandidatePaths(home)
            os.contains("mac") || os.contains("darwin") -> listOf(
                Path.of(home, "Library", "Application Support", "Claude")
            )
            os.contains("linux") -> listOf(Path.of(home, ".config", "Claude"))
            else -> return null
        }

        val existingPaths = candidatePaths.filter { it.exists() }
        if (existingPaths.size > 1) {
            logging.logToOutput("Warning: multiple Claude Desktop config directories found; using ${existingPaths.first()}: $existingPaths")
        }
        val basePath = existingPaths.firstOrNull() ?: return null

        return basePath.resolve(claudeConfigFileName)
    }

    internal fun windowsCandidatePaths(home: String): List<Path> {
        val traditional = Path.of(home, "AppData", "Roaming", "Claude")

        // Windows Store installs place config under a package directory with a random suffix:
        // AppData\Local\Packages\Claude_<suffix>\LocalCache\Roaming\Claude
        val packagesDir = Path.of(home, "AppData", "Local", "Packages")
        val storePaths = if (packagesDir.exists()) {
            packagesDir.listDirectoryEntries()
                .filter { it.isDirectory() && it.name.startsWith("Claude_") }
                .map { it.resolve("LocalCache").resolve("Roaming").resolve("Claude") }
        } else {
            emptyList()
        }

        return listOf(traditional) + storePaths
    }
}

/**
 * Registers the Burp MCP server in Gemini CLI's own settings file (`~/.gemini/settings.json`),
 * which is also where `gemini mcp add` writes, so the entry can be inspected and removed with the
 * Gemini CLI commands afterwards.
 *
 * Gemini CLI starts the packaged stdio proxy, rather than connecting to the SSE endpoint directly,
 * because the proxy reconnects on its own when Burp is restarted and works no matter whether the
 * MCP server was already running when Gemini CLI started.
 */
class GeminiCliProvider(
    private val logging: Logging,
    private val proxyJarManager: ProxyJarManager,
    private val homeDirectory: Path = Path.of(System.getProperty("user.home")),
    private val osName: String = System.getProperty("os.name"),
    private val javaHome: String = System.getProperty("java.home"),
) : Provider {

    private val settingsWriter = McpClientSettingsWriter(logging)

    override val name = "Gemini CLI"
    override val installButtonText = "Install to $name"
    override val confirmationText =
        "Install to $name?\nThis will create an entry within $name's MCP configuration file ($SETTINGS_LOCATION)"

    override fun install(config: McpConfig): String {
        val proxyJarFile = proxyJarManager.getProxyJar()

        val settingsFile = settingsFilePath()
        val creatingSettingsFile = !settingsFile.exists()

        val javaPath = javaExecutable(osName, javaHome)
        logging.logToOutput("Using Java from: $javaPath")

        val burpServerConfig = buildJsonObject {
            put("command", JsonPrimitive(javaPath))
            put("args", buildJsonArray {
                add(JsonPrimitive("-jar"))
                add(JsonPrimitive(proxyJarFile.toString()))
                add(JsonPrimitive("--sse-url"))
                add(JsonPrimitive(mcpServerUrl(config)))
            })
            // Shown by `gemini mcp list` and inside the CLI's /mcp output
            put("description", JsonPrimitive("Burp Suite security testing tools"))
        }

        settingsWriter.upsertServer(settingsFile, SERVER_NAME, burpServerConfig, indent = GEMINI_INDENT)

        logging.logToOutput("Installed Burp MCP Server to $name config")

        val created = if (creatingSettingsFile) "$SETTINGS_LOCATION was created.\n\n" else ""

        return "Installation successful. ${created}Please restart $name, then run " +
            "'$VERIFY_COMMAND' to check that the server is listed. Burp must be running with the " +
            "MCP server enabled for the tools to load."
    }

    internal fun settingsFilePath(): Path =
        homeDirectory.resolve(GEMINI_DIRECTORY).resolve(SETTINGS_FILE_NAME)

    companion object {
        internal const val SERVER_NAME = "burp"
        private const val GEMINI_DIRECTORY = ".gemini"
        private const val SETTINGS_FILE_NAME = "settings.json"

        /** Gemini CLI writes its settings with two space indentation */
        private const val GEMINI_INDENT = "  "
        private const val VERIFY_COMMAND = "gemini mcp list"
        private const val SETTINGS_LOCATION = "~/.gemini/settings.json"
    }
}

class ManualProxyInstallerProvider(private val logging: Logging, private val proxyJarManager: ProxyJarManager) :
    Provider {
    override val name = "Proxy jar"
    override val installButtonText = "Extract server proxy jar"
    override val confirmationText = null

    override fun install(config: McpConfig): String? {
        val proxyJarFile = proxyJarManager.getProxyJar()

        val fileChooser = JFileChooser().apply {
            dialogTitle = "Save proxy jar"
            selectedFile = File("mcp-proxy.jar")
        }

        if (fileChooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) {
            return null
        }

        val destinationFile = fileChooser.selectedFile
        try {
            Files.copy(proxyJarFile, destinationFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            logging.logToOutput("MCP proxy jar saved successfully to ${destinationFile.absolutePath}")
        } catch (ex: Exception) {
            logging.logToError("Failed to save installer: ${ex.message}")
            throw ex
        }

        return "Extracted proxy jar to $destinationFile"
    }
}

/**
 * The URL an MCP client running next to Burp should use to reach the MCP server.
 *
 * The server can be bound to a wildcard address to accept connections on every interface, which
 * isn't an address a client can connect back to, so the loopback address is used instead.
 */
internal fun mcpServerUrl(config: McpConfig): String {
    val host = when (val configured = config.host.trim()) {
        "", "*", "0.0.0.0", "::", "[::]" -> "127.0.0.1"
        else -> configured
    }

    return "http://$host:${config.port}"
}

/** The path of the Java runtime Burp is running on, as an MCP client will need to start the proxy itself. */
internal fun javaExecutable(
    osName: String = System.getProperty("os.name"),
    javaHome: String = System.getProperty("java.home"),
): String {
    val executable = if (osName.lowercase().contains("win")) "java.exe" else "java"

    return Path.of(javaHome, "bin", executable).toString()
}
