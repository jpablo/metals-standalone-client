# Repository Guidelines

## Project Structure & Module Organization
- Source: `src/main/scala/scala/meta/metals/standalone/`
- Tests: `src/test/scala/scala/meta/metals/standalone/`
- Build config: `project.scala` (scala-cli using directives)
- Formatting: `.scalafmt.conf`
- Binaries: `metals-standalone-client*` (generated in repo root)

## Build, Test, and Development Commands
- Compile: `scala-cli compile .`
- Run: `scala-cli run . -- [--verbose] [PROJECT_PATH]`
- Test: `scala-cli test .`
- Package (assembly JAR): `scala-cli --power package . --assembly -f -o metals-standalone-client`
- Package (standalone exe): `scala-cli --power package . --standalone -f -o metals-standalone-client`
- Quick build check: `./test-build.sh`

## Coding Style & Naming Conventions
- Language: Scala 3. Use modern syntax (fewer braces).
- Formatting: scalafmt with config in `.scalafmt.conf` (120 cols, align=most).
- Run formatter via your editor or `scalafmt` before committing.
- Names: classes/objects `PascalCase`, methods/vals `camelCase`, constants `UPPER_SNAKE_CASE`.
- Structure: prefer small, focused functions; keep side-effecting code near entry points.

## Testing Guidelines
- Framework: MUnit (`org.scalameta::munit`).
- Location: mirror source packages in `src/test/scala`.
- Naming: end files with `*Test.scala` (e.g., `MetalsLauncherTest.scala`).
- Scope: add tests for new behavior and regressions; use table-driven or property-style tests when practical.
- Run: `scala-cli test .` (CI and `test-build.sh` run the same).

## Commit & Pull Request Guidelines
- Commits: use clear, imperative messages (e.g., "Add health check retry logic").
- Group related changes; keep commits focused and buildable.
- PRs: include a concise description, rationale, and testing notes.
- Link related issues; add logs or screenshots for runtime behavior.
- Update docs when flags, outputs, or workflows change (e.g., README options).

## Security & Configuration Tips
- Do not commit secrets or local paths; MCP configs live in `.metals/`, `.cursor/`, or `.vscode/`.
- Default MCP SSE endpoint runs on `http://localhost:60013`; prefer local development ports.
- Keep changes compatible with scala-cli; avoid introducing sbt-only features.
