# Metals Standalone MCP Client

A standalone client that launches the Metals language server with MCP (Model Context Protocol) support, enabling AI assistants to interact with Scala projects.

## Purpose

This tool provides a headless way to run Metals language server with MCP capabilities, allowing AI code assistants like Claude to understand and work with your Scala codebase. It automatically discovers Metals installations, configures the language server, and monitors MCP server health.

## Quick Start

### Build
```bash
sbt stage
```

### Run
```bash
# Use current directory
./target/universal/stage/bin/metals-standalone-client

# Specify project path
./target/universal/stage/bin/metals-standalone-client /path/to/scala/project

# Enable verbose logging
./target/universal/stage/bin/metals-standalone-client --verbose .
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

The tool looks for MCP configuration files in:
- `.metals/mcp.json`
- `.cursor/mcp.json`
- `.vscode/mcp.json`

## Requirements

- Java 11 or higher
- Scala project with `build.sbt`
- SBT (for alternative execution via `sbt run`)

## Development

```bash
# Compile
sbt compile

# Run via SBT
sbt run [--verbose] [PROJECT_PATH]

# Test
sbt test

# Create distribution package
sbt Universal/packageBin
```