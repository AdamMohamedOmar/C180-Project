# android/ — scaffold status

This is a minimal Compose skeleton created in Phase 0. It has **not** been
gradle-synced or build-verified — the machine that scaffolded it has no
Android SDK, Gradle, or JDK 17+ installed.

To bring it up:
1. Open this `android/` folder as a project in Android Studio (Koala or newer).
2. Let Android Studio generate the Gradle wrapper and sync
   (File > Sync Project with Gradle Files). It will offer to create
   `gradlew`/`gradlew.bat` if they're missing — accept.
3. Confirm it builds and shows "KompressorLink — Phase 0 scaffold..." on an
   emulator or the S23 FE.
4. Real screens (dashboard, DTC, rides, trends, guided tests) are built in
   Phase 4 per `PLAN.md` §6.

Versions pinned in `gradle/libs.versions.toml` (AGP 8.7.2, Kotlin 2.0.20,
Compose BOM 2024.09.00) are best-effort-current as of plan authoring —
Android Studio may prompt an AGP/Gradle upgrade on first open; accept it.
