# Metals Standalone MCP Client

A standalone client that launches the Metals language server with MCP (Model Context Protocol) support, enabling AI assistants to interact with Scala projects. Built with scala-cli for fast compilation and easy distribution.

## Purpose

This tool provides a headless way to run Metals language server with MCP capabilities, allowing AI code assistants like Claude to understand and work with your Scala codebase. It automatically discovers Metals installations, configures the language server, and monitors MCP server health.

## Why is this needed and how does it work?

Metals is a Scala language server that normally runs inside an editor (VS Code, Neovim, etc.). The editor acts as an LSP client: it launches Metals, performs the initialization handshake, and sends configuration. Without an editor doing this work, Metals doesn't start.

This standalone client replaces the editor. It acts as a **headless LSP client** so that Metals can run without any editor open. Here's what happens when you run it:

1. The standalone client **discovers and launches Metals** as a subprocess (via Coursier).
2. It **performs the LSP handshake** over stdin/stdout (initialize, initialized) using JSON-RPC 2.0.
3. It **configures Metals to enable its built-in MCP server** by sending `startMcpServer: true`.
4. Metals starts the MCP server and **writes a `.mcp.json` file** at your project root with the connection URL:
   ```json
   {
     "mcpServers": {
       "metals": {
         "url": "http://localhost:<port>/mcp",
         "type": "http"
       }
     }
   }
   ```
5. The standalone client **monitors the MCP server health** via HTTP polling to make sure it stays up.

Once this is running, you can open Claude Code (or another MCP-aware AI assistant) in your Scala project directory.

This gives AI assistants access to Scala language intelligence (go-to-definition, find-references, completions, diagnostics, etc.) without requiring an IDE.

For a detailed diagram and component breakdown, see [ARCHITECTURE.md](ARCHITECTURE.md).

## Quick Start

### Download Pre-built Executables

**Linux:**
```bash
wget https://github.com/jpablo/metals-standalone-client/releases/latest/download/metals-standalone-client-linux-executable
chmod +x metals-standalone-client-linux-executable
./metals-standalone-client-linux-executable --help
```

**macOS:**
```bash
wget https://github.com/jpablo/metals-standalone-client/releases/latest/download/metals-standalone-client-macos-executable
chmod +x metals-standalone-client-macos-executable
./metals-standalone-client-macos-executable --help
```

**Windows:**
```cmd
REM Download both files to the same directory:
REM - metals-standalone-client-windows-executable.bat
REM - metals-standalone-client-windows-executable.jar
metals-standalone-client-windows-executable.bat --help
```

### Build from Source

```bash
# Build assembly JAR
scala-cli --power package . --assembly -f -o metals-standalone-client

# Or build standalone executable
scala-cli --power package . --standalone -f -o metals-standalone-client
```

### Run from Source
```bash
# Use current directory
scala-cli run .

# Specify project path and options
scala-cli run . -- --verbose /path/to/scala/project
```

### Options
- `-v, --verbose`: Enable verbose logging
- `-h, --help`: Show help message

## Features

- **Multiple Metals Discovery**: Supports Coursier, SBT development mode, local JARs
- **Headless Operation**: Runs without IDE dependencies
- **MCP Integration**: Automatically configures Metals MCP server
- **Health Monitoring**: Continuous monitoring with automatic reconnection
- **Cross-platform**: Works on Linux, macOS, and Windows

## Configuration

### Using with Claude Code

Once the standalone client is running, isntructions will be printed to the console. Simply follow these instructions in your project directory. You'll be prompted to approve the MCP server on first use.

### MCP Configuration Files

Metals writes MCP configuration to different locations depending on the detected client:

| Client | Config path |
|--------|-------------|
| Claude Code | `.mcp.json` (project root) |
| Cursor | `.cursor/mcp.json` |
| VS Code | `.vscode/mcp.json` |
| No client detected | `.metals/mcp.json` |

### Metals Version Override

When Metals is discovered via Coursier, you can pin the version:
```bash
METALS_VERSION=1.6.0 ./metals-standalone-client --help
```

## Requirements

### For Pre-built Executables
- Java 11 or higher
- Scala project with `build.sbt`

### For Building from Source
- Java 11 or higher
- scala-cli (install from https://scala-cli.virtuslab.org)
- Scala project with `build.sbt`, `project.scala`, or `.scala` files

## Project Structure

This project uses scala-cli for build management. The configuration is defined in `project.scala` using scala-cli's "using directives":

```scala
//> using scala "3.7.1"
//> using dep "io.circe::circe-core:0.14.14"
//> using dep "io.circe::circe-parser:0.14.14"
// ... other dependencies
```

This approach provides:
- Fast compilation and startup times
- Self-contained dependency management
- Simple configuration in a single file
- Perfect for MCP server use cases where simplicity and speed matter

## Development

```bash
# Compile
scala-cli compile .

# Run application
scala-cli run . -- [--verbose] [PROJECT_PATH]

# Test
scala-cli test .

# Build assembly JAR
scala-cli --power package . --assembly -f -o metals-standalone-client

# Build standalone executable
scala-cli --power package . --standalone -f -o metals-standalone-client

# Quick build test (compile + test + package)
./test-build.sh
```

## Automated Builds

This project uses GitHub Actions to automatically build and release executables for Linux, macOS, and Windows on every push to the main branch. The releases include single executable files that are ready to use without compilation.
