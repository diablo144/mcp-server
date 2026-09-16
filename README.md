# Burp Suite MCP Server Extension

## Overview

Integrate Burp Suite with AI Clients using the Model Context Protocol (MCP).

For more information about the protocol visit: [modelcontextprotocol.io](https://modelcontextprotocol.io/)

## Features

- Connect Burp Suite to AI clients through MCP
- Automatic installation for Claude Desktop and Gemini CLI
- Comes with packaged Stdio MCP proxy server

## Usage

- Install the extension in Burp Suite
- Configure your Burp MCP server in the extension settings
- Configure your MCP client to use the Burp SSE MCP server or stdio proxy
- Interact with Burp through your client!

## Installation

### Prerequisites

Ensure that the following prerequisites are met before building and installing the extension:

1. **Java**: Java must be installed and available in your system's PATH. You can verify this by running `java --version` in your terminal.
2. **jar Command**: The `jar` command must be executable and available in your system's PATH. You can verify this by running `jar --version` in your terminal. This is required for building and installing the extension.

### Building the Extension

1. **Clone the Repository**: Obtain the source code for the MCP Server Extension.
   ```
   git clone https://github.com/PortSwigger/mcp-server.git
   ```

2. **Navigate to the Project Directory**: Move into the project's root directory.
   ```
   cd mcp-server
   ```

3. **Build the JAR File**: Use Gradle to build the extension.
   ```
   ./gradlew embedProxyJar
   ```

   This command compiles the source code and packages it into a JAR file located in `build/libs/burp-mcp-all.jar`.

### Loading the Extension into Burp Suite

1. **Open Burp Suite**: Launch your Burp Suite application.
2. **Access the Extensions Tab**: Navigate to the `Extensions` tab.
3. **Add the Extension**:
    - Click on `Add`.
    - Set `Extension Type` to `Java`.
    - Click `Select file ...` and choose the JAR file built in the previous step.
    - Click `Next` to load the extension.

Upon successful loading, the MCP Server Extension will be active within Burp Suite.

## Configuration

### Configuring the Extension
Configuration for the extension is done through the Burp Suite UI in the `MCP` tab.
- **Toggle the MCP Server**: The `Enabled` checkbox controls whether the MCP server is active.
- **Enable config editing**: The `Enable tools that can edit your config` checkbox allows the MCP server to expose tools which can edit Burp configuration files.
- **Advanced options**: You can configure the port and host for the MCP server. By default, it listens on `http://127.0.0.1:9876`.

### Claude Desktop Client

To fully utilize the MCP Server Extension with Claude, you need to configure your Claude client settings appropriately.
The extension has an installer which will automatically configure the client settings for you.

1. Currently, Claude Desktop only support STDIO MCP Servers
   for the service it needs.
   This approach isn't ideal for desktop apps like Burp, so instead, Claude will start a proxy server that points to the
   Burp instance,  
   which hosts a web server at a known port (`localhost:9876`).

2. **Configure Claude to use the Burp MCP server**  
   You can do this in one of two ways:

    - **Option 1: Run the installer from the extension**
      This will add the Burp MCP server to the Claude Desktop config.

    - **Option 2: Manually edit the config file**  
      Open the file located at `~/Library/Application Support/Claude/claude_desktop_config.json`,
      and replace or update it with the following:
      ```json
      {
        "mcpServers": {
          "burp": {
            "command": "<path to Java executable packaged with Burp>",
            "args": [
                "-jar",
                "/path/to/mcp/proxy/jar/mcp-proxy-all.jar",
                "--sse-url",
                "<your Burp MCP server URL configured in the extension>"
            ]
          }
        }
      }
      ```

3. **Restart Claude Desktop** - assuming Burp is running with the extension loaded.

### Gemini CLI Client

The extension can configure the Gemini CLI for you as well. Press `Install to Gemini CLI` in the
extension settings and the Burp MCP server is added to `~/.gemini/settings.json` (on Windows that is
`%USERPROFILE%\.gemini\settings.json`). Anything else in that file - your auth type, theme, other MCP
servers - is left as it is, and an existing `burp` entry is replaced rather than duplicated.

Gemini CLI starts the packaged stdio proxy itself, so the connection recovers on its own when Burp is
restarted. After installing:

1. Start Burp with the extension loaded and the MCP server enabled.
2. Run `gemini mcp list` to check that `burp` is configured, and `gemini` to load the tools.
   Inside a session, `/mcp` shows the tools that were discovered.
3. Keep the folder you run Gemini CLI from trusted. Gemini CLI disables MCP servers in folders you
   haven't trusted, which is the usual reason for the tools not showing up at all.

Every Burp tool call is confirmed by Gemini CLI, and requests to targets you haven't approved are
confirmed by Burp. If you want Gemini CLI to skip its own prompts for this server, add
`"trust": true` to the entry. Prefer to let Burp do the approval instead, which is the default.

To do the same thing from a terminal, or to connect over SSE instead of the proxy, use the Gemini CLI
installer:

```bash
# stdio, through the proxy packaged with the extension
gemini mcp add --scope user burp "/path/to/packaged/java" -jar /path/to/mcp-proxy-all.jar --sse-url http://127.0.0.1:9876

# or point Gemini CLI at the extension's SSE server directly, without the proxy
gemini mcp add --scope user --transport sse burp http://127.0.0.1:9876
```

The server exposes its SSE endpoint at the configured root URL, so no `/sse` suffix is needed.

The Burp MCP server exposes a lot of tools, which costs context on every prompt. Gemini CLI can limit
them per server with `includeTools` / `excludeTools`, for example to keep a scan focused on traffic
instead of configuration:

```json
{
  "mcpServers": {
    "burp": {
      "command": "/path/to/packaged/java",
      "args": [
        "-jar",
        "/path/to/mcp-proxy-all.jar",
        "--sse-url",
        "http://127.0.0.1:9876"
      ],
      "excludeTools": ["set_project_options", "set_user_options", "set_active_editor_contents"],
      "timeout": 600000
    }
  }
}
```

`gemini mcp remove burp` removes the entry again.

#### Troubleshooting

- **`gemini mcp list` reports the server as `Disabled`** - the current folder isn't trusted. Gemini
  CLI refuses to start MCP servers in folders you haven't trusted; trust it and start again.
- **The server is listed but no tools load** - Burp isn't running or the MCP server toggle is off.
  The proxy reconnects by itself once Burp's server answers.
- **Tools disappeared after changing the port or host** - the installed entry contains the address
  from when it was written. Press `Install to Gemini CLI` again.
- **`✗ burp: ... (stdio) - Disconnected` with a java path that no longer exists** - Burp's bundled
  runtime moved, typically after an upgrade, or Burp itself is sandboxed (Snap/Flatpak) so the
  client can't execute it. Re-run the installer, point `command` at a Java on your `PATH`, or
  connect over SSE instead.

## Manual installations
If you want to install the MCP server manually you can either use the extension's SSE server directly or the packaged
Stdio proxy server.

### SSE MCP Server
To use the SSE server directly, provide the configured server URL to your MCP client:
```
http://127.0.0.1:9876
```

### Stdio MCP Proxy Server
The source code for the proxy server can be found here: [MCP Proxy Server](https://github.com/PortSwigger/mcp-proxy)

In order to support MCP Clients which only support Stdio MCP Servers, the extension comes packaged with a proxy server for
passing requests to the SSE MCP server extension.

If you want to use the Stdio proxy server you can use the extension's installer option to extract the proxy server jar.
Once you have the jar you can add the following command and args to your client configuration:
```
/path/to/packaged/burp/java -jar /path/to/proxy/jar/mcp-proxy-all.jar --sse-url http://127.0.0.1:9876
```

If you modify the proxy source, rebuild and copy it into this project before packaging the extension:
```bash
# From mcp-proxy
./gradlew shadowJar
cp build/libs/mcp-proxy-all.jar /path/to/mcp-server/libs/mcp-proxy-all.jar

# From mcp-server
./gradlew embedProxyJar
```

### Creating / modifying tools

Tools are defined in `src/main/kotlin/net/portswigger/mcp/tools/Tools.kt`. To define new tools, create a new serializable
data class with the required parameters which will come from the LLM.

The tool name is auto-derived from its parameters data class. A description is also needed for the LLM. You can return
a string or a `List<ContentBlock>` to provide data back to the LLM.

Extend the Paginated interface to add auto-pagination support.
