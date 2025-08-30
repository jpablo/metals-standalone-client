# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

- **Compile**: `scala-cli compile .`
- **Run application**: `scala-cli run . [-- [--verbose] [PROJECT_PATH]]`
- **Test**: `scala-cli test .`
- **Package (Assembly JAR)**: `scala-cli --power package . --assembly -o metals-standalone-client`
- **Package (Standalone)**: `scala-cli --power package . --standalone -o metals-standalone-client`
- **Quick build test**: `./test-build.sh`

The main class is `scala.meta.metals.standalone.Main` and is configured in `project.scala`. The executable JAR is created as `metals-standalone-client` in the current directory.

## Architecture

This is a Scala application that provides a standalone MCP (Model Context Protocol) client for Metals language server. The architecture consists of several key components:

### Core Components

- **Main.scala** (`scala.meta.metals.standalone.Main`): Entry point and application lifecycle management
- **MetalsLauncherK.scala**: Kyo-based Metals launcher (Coursier, SBT dev, JAR, direct)
- **LspClientK.scala**: Kyo-based LSP client implementing JSON-RPC 2.0 over stdin/stdout for Metals
- **MetalsClientK.scala**: Kyo-based orchestrator for Metals initialization and configuration
- **McpMonitorK.scala**: Kyo-based MCP configuration discovery and health monitoring
- Removed legacy non-Kyo components (MetalsClient.scala, LspClient.scala, McpMonitor.scala) after Kyo migration.

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

Built with Scala 3.7.1 using scala-cli and configured in `project.scala`:
- Circe for JSON processing
- STTP for HTTP client functionality
- Coursier for Metals discovery
- MUnit for testing

The application creates a fat JAR for easy distribution and can run against any Scala project directory.
