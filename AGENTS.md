# Repository Guidelines

## Project Structure & Module Organization
- `src/main/scala/scala/meta/metals/standalone/`: application sources (Main, launcher, LSP/MCP clients).
- `src/test/scala/scala/meta/metals/standalone/`: MUnit tests; filenames end with `*Test.scala`.
- `project.scala`: scala-cli build config and dependencies.
- `test-build.sh`: quick build script (compile + test + package).
- `target/`: build outputs from scala-cli (generated).

## Build, Test, and Development Commands
```bash
scala-cli compile .                      # Compile sources
scala-cli run . -- [--verbose] [PATH]    # Run CLI (optional project path)
scala-cli test .                         # Run MUnit tests
scala-cli --power package . --assembly -o metals-standalone-client
scala-cli --power package . --standalone -o metals-standalone-client
./test-build.sh                          # Quick compile/test/package
```
The entry point is `scala.meta.metals.standalone.Main` (see `project.scala`).

## Coding Style & Naming Conventions
- Scala 3 with significant indentation; follow existing “fewer braces” style in main sources.
- Use 2-space indentation and align multiline params as in current files.
- Class/object names are `UpperCamelCase`; file names match class/object names.
- Prefer `java.util.logging` via `Logger` over `println`.

## Testing Guidelines
- Framework: MUnit (`munit.FunSuite`).
- Test files live in `src/test/scala/...` and end with `*Test.scala`.
- Keep test names descriptive (e.g., `"validateProject returns false for file"`).
- No explicit coverage target; add/update tests for behavior changes.

## Commit & Pull Request Guidelines
- Commit messages use imperative, sentence-style summaries (e.g., `Update README.md...`).
- PRs should include:
  - Short description of the change and rationale.
  - Testing notes (commands + results).
  - Linked issue if applicable.
  - CLI output snippet if behavior changes are user-facing.

## Configuration & MCP Notes
- MCP config files are discovered in `.metals/mcp.json`, `.cursor/mcp.json`, or `.vscode/mcp.json`.
- Keep new configuration defaults documented in `README.md`.
