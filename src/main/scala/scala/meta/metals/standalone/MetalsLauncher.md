# MetalsLauncher — Kyo Effect Overview

Purpose
- Provide a Kyo-based (effectful) implementation of the Metals launcher.
- Make side effects explicit and composable using Kyo’s `Sync` and `Process` APIs.
- This is the canonical launcher; previous non‑Kyo version has been removed.

What It Does
- Discovery order: Coursier (`cs`/`coursier`), SBT development, local Metals JAR, direct `metals` command.
- Launch: spawns the Metals process via `kyo.Process.Command`, returning `Option[kyo.Process] < Sync`.
- Validation: checks project path existence and detects Scala projects (SBT, Mill, Maven, Gradle, Scala CLI, or `.scala` files).
- Shutdown: destroys the spawned process; force-kills if needed.

Key API (effects require `using Frame`)
- `findMetalsInstallation(): Option[MetalsInstallation] < Sync`
- `launchMetals(): Option[kyo.Process] < Sync`
- `validateProject(): Boolean < Sync`
- `isScalaProject(): Boolean < Sync`
- `shutdown(): Unit < Sync`

Implementation Notes
- No blocking process I/O loops; relies on `kyo.Process` wrappers for `stdin/stdout/stderr` and lifecycle.
- Executable discovery avoids external `which` by scanning `PATH` and honoring `JAVA_HOME` for `java`.
- Side effects (logging, FS checks, env lookups) are wrapped in `Sync.defer`.

Using From Non‑Kyo Code
- Run Kyo effects with `Sync.Unsafe.evalOrThrow(...)` when integrating from non‑Kyo contexts (tests, simple utilities).

```scala
import kyo.*
import java.nio.file.Paths

val launcher = new MetalsLauncher(Paths.get("."))
val ok       = Sync.Unsafe.evalOrThrow(launcher.validateProject())
if (ok) {
  val procOpt = Sync.Unsafe.evalOrThrow(launcher.launchMetals())
  // ... use procOpt; later:
  Sync.Unsafe.evalOrThrow(launcher.shutdown())
}
```

Using Within Kyo Code
- If you already run inside Kyo (e.g., a `KyoApp`), call methods directly with an implicit `Frame` and compose with other effects.

Notes
- Effectful logging via `kyo.Log` replaces `java.util.logging`.
- Executable discovery avoids external `which` to remain portable.
