# Scala 3 Migration Plan

## Summary

The metals-standalone-client project can be migrated to Scala 3 with minimal changes and very low risk. All major dependencies already support Scala 3, and the code uses modern Scala patterns that are compatible with Scala 3.

## Key Insight: Metals as External Process

**Important**: The Metals language server reference in `MetalsLauncher.scala` does NOT need to be updated during migration:

```scala
val command = Seq(cs, "fetch", "--classpath", "org.scalameta:metals_2.13:1.6.0")
```

This is because:
- Metals runs as a separate JVM process launched via Coursier
- Communication happens over LSP protocol (JSON-RPC), not direct API calls
- Scala version mismatch between client and server doesn't matter
- Metals can work with Scala 3 projects regardless of what Scala version it was built with

## Required Changes

### 1. Update build.sbt

```scala
// Change this:
ThisBuild / scalaVersion := "2.13.16"

// To:
ThisBuild / scalaVersion := "3.7.1" // or latest Scala 3.x

// Update compiler options:
scalacOptions ++= List(
  "-deprecation",
  "-unchecked", 
  "-feature",
  // Remove Scala 2 specific flags:
  // "-Xlint",      // Replace with Scala 3 equivalents
  // "-Xfatal-warnings"  // May need adjustment for Scala 3
)
```

### 2. Optional Code Improvements

#### Replace implicit parameters with using clauses
```scala
// Current (Scala 2/3 compatible):
class LspClient(process: Process)(implicit ec: ExecutionContext)

// Scala 3 style (optional):
class LspClient(process: Process)(using ExecutionContext)
```

#### Consider converting sealed trait + case classes to enums
```scala
// Current:
sealed trait MetalsInstallation
case class CoursierInstallation(javaExecutable: String, classpath: String) extends MetalsInstallation
case class SbtDevelopment(sbtExecutable: String, repoDir: Path) extends MetalsInstallation
// ... etc

// Scala 3 enum (optional):
enum MetalsInstallation:
  case CoursierInstallation(javaExecutable: String, classpath: String)
  case SbtDevelopment(sbtExecutable: String, repoDir: Path)
  // ... etc
```

## Dependencies Analysis

All current dependencies already support Scala 3:

- ✅ **Circe** (0.14.14) - Scala 3 compatible
- ✅ **STTP Client** (3.11.0) - Scala 3 compatible  
- ✅ **MUnit** (1.1.1) - Scala 3 compatible
- ✅ **scopt** (4.1.0) - Scala 3 compatible

No dependency version changes required.

## Code Patterns Analysis

The codebase uses modern Scala patterns that are compatible with Scala 3:

- ✅ No deprecated features that are removed in Scala 3
- ✅ No procedure syntax
- ✅ No package objects
- ✅ No problematic operator definitions
- ✅ Already using `scala.jdk.CollectionConverters._` for Java interop
- ✅ Modern pattern matching (no issues expected)

## Risk Assessment: VERY LOW

- All dependencies already support Scala 3
- Metals reference is just a string for external process
- LSP communication protocol is version-agnostic
- Good test coverage (57 tests) to catch any issues
- Modern idiomatic Scala patterns throughout

## Migration Steps

1. **Update build.sbt** with Scala 3 version and compiler flags
2. **Run tests** to verify everything compiles and works
3. **Optional**: Apply Scala 3 style improvements (using clauses, enums)
4. **Update CI/CD** if needed to use Scala 3

## Testing Strategy

- Existing test suite should catch any compatibility issues
- Pay special attention to:
  - JSON serialization/deserialization (Circe)
  - HTTP client functionality (STTP)
  - Process launching and LSP communication
  - File I/O and path handling

## Next Steps

When ready to migrate:
1. Create a feature branch
2. Update `scalaVersion` in build.sbt
3. Fix any compilation errors
4. Run full test suite
5. Test with actual Metals integration
6. Merge when confident

The migration should be straightforward with no major refactoring required.
