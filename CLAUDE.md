# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

- **Compile**: `sbt compile`
- **Package**: `sbt stage` (creates executable in target/universal/stage/bin/)
- **Universal package**: `sbt Universal/packageBin` (creates distributable archive) 
- **Run application**: `sbt run [--verbose] [PROJECT_PATH]`
- **Test**: `sbt test`
- **Quick build test**: `./test-build.sh`

The main class is `scala.meta.metals.standalone.Main` and the executable is created as `target/universal/stage/bin/metals-standalone-client` (or `metals-standalone-client.bat` on Windows).

## Architecture

This is a Scala application that provides a standalone MCP (Model Context Protocol) client for Metals language server. The architecture consists of several key components:

### Core Components

- **Main.scala** (`scala.meta.metals.standalone.Main`): Entry point and application lifecycle management
- **MetalsLauncher.scala**: Discovers and launches Metals language server via multiple methods (Coursier, SBT development, JAR files)
- **LspClient.scala**: Minimal LSP client implementing JSON-RPC 2.0 protocol over stdin/stdout for Metals communication
- **MetalsClient.scala**: Higher-level Metals-specific client that handles LSP initialization and MCP server configuration
- **McpMonitor.scala**: Monitors MCP server health and configuration files, provides connection instructions

### Key Features

- **Multiple Metals Discovery**: Supports Coursier installation, SBT development mode, local JARs, and direct commands
- **Headless Operation**: Implements minimal LSP client capabilities for headless environments without IDE
- **MCP Integration**: Automatically configures and monitors Metals MCP server for AI assistant integration
- **Health Monitoring**: Continuous monitoring of MCP server health with automatic reconnection

### Configuration

The application looks for MCP configuration files in:
- `.metals/mcp.json`
- `.cursor/mcp.json` 
- `.vscode/mcp.json`

### Dependencies

Built with Scala 3.7.1 using:
- Circe for JSON processing
- STTP for HTTP client functionality
- Coursier for Metals discovery
- MUnit for testing

The application creates a fat JAR for easy distribution and can run against any Scala project directory.
