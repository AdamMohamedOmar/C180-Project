# android/ — agent build environment

Recorded 2026-07-12, during Phase 4 execution. Read this before running any
Gradle command in later tasks — it's the exact working invocation.

## How this environment came to exist (deviation from the plan's Task 0)

The plan's Task 0 expected the **user** to install Android Studio, open
`android/` in the Studio GUI, and let Studio's first-sync wizard generate
the Gradle wrapper and provision the SDK interactively. That GUI flow isn't
drivable by an agent (no desktop-automation tool available here — only a
Chrome-browser automation tool, not a general GUI driver).

With the user's explicit go-ahead, the agent instead bootstrapped the same
end state entirely from the command line — the standard CI approach:

1. Installed Android Studio via `winget install --id Google.AndroidStudio
   --silent` (official Google source via the Windows Package Manager).
2. Downloaded the standalone Android SDK command-line tools
   (`commandlinetools-win-14742923_latest.zip`, ~150 MB, from
   `dl.google.com`) and laid them out at
   `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\`.
3. Accepted all 7 SDK package licenses via `sdkmanager --licenses` (answers
   fed via a redirected-stdin file — piping directly through PowerShell
   didn't work reliably against sdkmanager's interactive prompt).
4. Installed `platform-tools`, `platforms;android-35`, `build-tools;35.0.0`
   via `sdkmanager`. (AGP additionally auto-installed `build-tools;34.0.0`
   during the first `assembleDebug` run — both are now present; harmless.)
5. Wrote `android/local.properties`: `sdk.dir=C:/Users/DELL/AppData/Local/Android/Sdk`
   (forward slashes — avoids Windows-path-escaping issues in a Java
   properties file).
6. Downloaded Gradle 8.9 (`gradle-8.9-bin.zip`, ~130 MB, from
   `services.gradle.org` — the minimum version AGP 8.7.2 requires) and used
   its bundled `gradle.bat` to run `gradle wrapper --gradle-version 8.9
   --distribution-type bin` inside `android/`, generating `gradlew.bat`,
   `gradlew`, and `gradle/wrapper/gradle-wrapper.{jar,properties}` the
   normal, consistent way (not hand-authored).

**Consequence:** no AGP/Kotlin/Compose upgrade prompts were seen or
accepted, because there was no interactive Studio sync — the versions
already pinned in `gradle/libs.versions.toml` (AGP 8.7.2, Kotlin 2.0.20,
Compose BOM 2024.09.00) were used as-is and built successfully with zero
changes. **The user should still open the project in Android Studio at
their convenience** (Task 0's original Steps 2-3) to confirm the IDE itself
opens the project cleanly and to run the scaffold on the S23 FE over USB —
that visual/on-device confirmation is not something this headless
bootstrap proves. If Studio's own sync later prompts an upgrade, accept it
and update this file's version note.

## Working headless invocation

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"   # bundled JBR, OpenJDK 21.0.10
cd "D:\C180 Project\android"
.\gradlew.bat :app:assembleDebug        # BUILD SUCCESSFUL in 3m 31s (first run), 35 tasks
.\gradlew.bat :app:testDebugUnitTest    # BUILD SUCCESSFUL in 2s (no test sources yet)
```

Both commands were run for real and both printed `BUILD SUCCESSFUL` — this
isn't inferred, it was executed.

## Key paths

- `JAVA_HOME` candidate that works: `C:\Program Files\Android\Android Studio\jbr`
  (OpenJDK 21.0.10, bundled with Android Studio — no separate JDK install
  needed).
- SDK root: `C:\Users\DELL\AppData\Local\Android\Sdk`
  (= `local.properties`'s `sdk.dir`, forward-slash form).
- `ANDROID_HOME`/`ANDROID_SDK_ROOT` env vars are **not** set system-wide;
  Gradle finds the SDK via `local.properties` alone, which is sufficient —
  don't assume `ANDROID_HOME` is set in a fresh shell.
- Gradle wrapper's own distribution (`gradle-8.9-bin.zip`) is cached under
  the default `%USERPROFILE%\.gradle\wrapper\dists\` after the first
  `gradlew.bat` invocation downloaded it — subsequent runs don't re-download.

## For later tasks

Every Android task's verification step in the plan that says "run
`gradlew.bat ...`" means: set `$env:JAVA_HOME` as above first (a fresh
PowerShell session won't have it), then run from `D:\C180 Project\android\`.
No other environment setup should be needed.
