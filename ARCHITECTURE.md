# Architecture

## Process Diagram

```mermaid
graph TD
    subgraph client["metals-standalone-client<br/><i>scala.meta.metals.standalone.Main</i>"]
        ML[MetalsLauncher<br/><small>Spawns Metals JVM process</small>]
        LC[LspClient<br/><small>JSON-RPC 2.0 over stdin/stdout</small>]
        MC[MetalsClient<br/><small>LSP initialize handshake + config</small>]
        MM[McpMonitor<br/><small>Polls .metals/mcp.json & HTTP health</small>]
    end

    subgraph metals["Metals Language Server<br/><i>scala.meta.metals.Main</i><br/><small>Java 21 via Coursier</small>"]
        LSP[LSP Server<br/><small>lsp4j</small>]
        BSP[Build Server<br/><small>BSP / Bloop / sbt</small>]
        MCP[MCP Server<br/><small>HTTP + SSE</small>]
    end

    subgraph ai["AI Assistants"]
        Claude["Claude Code / Cursor / etc.<br/><small>claude mcp add --transport sse metals URL/sse</small>"]
    end

    ML -- "spawns process" --> metals
    LC -- "stdin/stdout<br/>(JSON-RPC / LSP)" --> LSP
    MC -- "initialize → initialized<br/>configureMetals<br/>(startMcpServer: true)" --> LSP
    MM -- "HTTP GET<br/>(health check)" --> MCP
    Claude -- "HTTP SSE<br/>(MCP protocol)" --> MCP
```

## How It Works

The **standalone client** acts as a **headless LSP client** — it replaces VS Code/Neovim as the "editor" side of the LSP protocol. Its sole purpose is to:

1. **Launch** the real Metals language server as a subprocess via Coursier
2. **Perform the LSP handshake** (initialize, initialized, etc.) over stdin/stdout using JSON-RPC 2.0
3. **Enable Metals' built-in MCP server** by sending `startMcpServer: true` in the configuration
4. **Monitor** that the MCP HTTP endpoint comes up healthy

Once both processes are running, the Metals MCP server exposes an **HTTP+SSE endpoint** that AI assistants like Claude Code can connect to, giving them access to Scala language intelligence (definitions, references, diagnostics, etc.) — all without needing an actual editor open.

## Components

| Component | File | Role |
|---|---|---|
| `MetalsLauncher` | `MetalsLauncher.scala` | Discovers Metals installations (Coursier, sbt, JAR, PATH) and spawns the JVM process |
| `LspClient` | `LspClient.scala` | Implements JSON-RPC 2.0 over stdin/stdout, handles server notifications and requests |
| `MetalsClient` | `MetalsClient.scala` | Drives the LSP initialize handshake and sends configuration to enable MCP |
| `McpMonitor` | `McpMonitor.scala` | Watches for `.metals/mcp.json`, extracts the MCP URL, and polls server health |
| `Main` / `MetalsLight` | `Main.scala` | Entry point that orchestrates all components in sequence |
