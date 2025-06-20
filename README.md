# Metals Standalone MCP Client

A standalone client that launches the Metals language server with MCP (Model Context Protocol) support, enabling AI assistants to interact with Scala projects.

## Purpose

This tool provides a headless way to run Metals language server with MCP capabilities, allowing AI code assistants like Claude to understand and work with your Scala codebase. It automatically discovers Metals installations, configures the language server, and monitors MCP server health.

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
# Build fat JAR
sbt assembly

# Or build with native packager
sbt stage
```

### Run from Source
```bash
# Use current directory
sbt run

# Specify project path and options
sbt "run --verbose /path/to/scala/project"
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

### For Pre-built Executables
- Java 11 or higher
- Scala project with `build.sbt`

### For Building from Source
- Java 11 or higher
- SBT 1.11.2 or higher
- Scala project with `build.sbt`

## Development

```bash
# Compile
sbt compile

# Run via SBT
sbt run [--verbose] [PROJECT_PATH]

# Test
sbt test

# Build fat JAR
sbt assembly

# Build with native packager
sbt stage

# Create distribution package
sbt Universal/packageBin
```

## Automated Builds

This project uses GitHub Actions to automatically build and release executables for Linux, macOS, and Windows on every push to the main branch. The releases include single executable files that are ready to use without compilation.