plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.kompressorlink.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kompressorlink.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.0-phase4.5"

        // Debug-only escape hatch (rides/RidesViewModel.kt): when non-empty,
        // sync() talks straight to this base URL instead of doing BLE 0x03 +
        // SoftAP join -- points at firmware/tools/kl_sync_dev_server.py for
        // phone acceptance without any device hardware (Task 13). Empty
        // string = disabled, always use the real BLE+WiFi path. Never commit
        // a real value here; override locally in the debug{} block below or
        // via a gradle.properties-injected value if that becomes annoying.
        buildConfigField("String", "KL_SYNC_DEV_SERVER", "\"\"")
    }

    buildFeatures {
        compose = true
        // Required for the defaultConfig/buildTypes buildConfigField(...)
        // calls above/below -- off by default on this AGP version, and
        // calling buildConfigField without it fails the build.
        buildConfig = true
    }

    buildTypes {
        debug {
            // Local override point: point this at your PC's LAN address
            // while running kl_sync_dev_server.py (Task 13 documents the
            // exact value) to exercise the Rides tab without the ESP32.
            // Leave empty to use the real BLE+SoftAP path.
            buildConfigField("String", "KL_SYNC_DEV_SERVER", "\"\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged Android resources on the JVM
            // test classpath (DAO tests, worker smoke checks).
            isIncludeAndroidResources = true
        }
    }

    // Room's MigrationTestHelper loads exported schema JSONs from assets at
    // runtime. This project configures room.schemaLocation via a raw ksp
    // arg (not the androidx.room Gradle plugin), so that wiring isn't
    // automatic. Robolectric unit tests (isIncludeAndroidResources = true,
    // above) read the DEBUG variant's own merged assets — confirmed via the
    // generated test_config.properties (android_merged_assets points at
    // mergeDebugAssets), not a test-specific source set — so the schemas
    // dir is added to "debug" specifically, keeping it out of release
    // builds. Without this, MigrationTest fails with FileNotFoundException:
    // "Cannot find the schema file in the assets folder"
    // (https://developer.android.com/training/data-storage/room/migrating-db-versions#export-schema).
    sourceSets {
        getByName("debug") {
            assets.srcDirs("$projectDir/schemas")
        }
    }
}

// Room: write the schema JSON into version control so future phases can
// write honest migrations against it (spec §2).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// SyncClientTest (sync/SyncClientTest.kt) exercises a real local HTTP
// server via the JDK-bundled com.sun.net.httpserver — deliberately, per
// docs/superpowers/plans/2026-07-17-app-dynamic-health-and-sync.md Task 11,
// so the test runs against real socket/HTTP behavior instead of a mock.
//
// AGP compiles this module's Kotlin (both the main and unit-test variants)
// against android.jar as the platform classpath; the real desktop JDK isn't
// consulted at all (confirmed by probing: even a plain java.se class never
// touched by Android, e.g. javax.swing.JFrame, fails to resolve the same
// way com.sun.net.httpserver does). So jdk.httpserver has to be supplied
// explicitly for tests to compile.
//
// Rather than checking in a binary jar tied to one JDK build, extract the
// module's classes from whatever JDK is actually running this build
// (`java.home`, i.e. org.gradle.java.home from gradle.properties) using the
// JDK's own `jimage` tool — reproducible on any machine, self-updates if
// the JDK changes. Only needed at compile time: at test *runtime* the real
// JVM's own platform classloader resolves com.sun.net.httpserver from the
// actual JDK module regardless of what's on the application classpath, so
// nothing extra is needed to run (see the KotlinCompile.libraries wiring
// below — that's compile-classpath-only, never touches the runtime
// classpath).
val jdkHttpserverStubDir = layout.buildDirectory.dir("jdkHttpserverStub")

val extractJdkHttpserverStub by tasks.registering(Exec::class) {
    val javaHomeDir = System.getProperty("java.home")
    val outDir = jdkHttpserverStubDir.get().asFile
    inputs.property("javaHome", javaHomeDir)
    outputs.dir(outDir)
    doFirst {
        outDir.deleteRecursively()
        outDir.mkdirs()
    }
    commandLine(
        "$javaHomeDir/bin/jimage", "extract",
        "--dir=${outDir.absolutePath}",
        "$javaHomeDir/lib/modules",
    )
    // module-info.class at the root of a classpath directory can make
    // javac/kotlinc treat it as a JPMS module (needing --module-path)
    // instead of a plain classpath entry, and silently ignore its classes.
    // We only want plain classpath resolution, so drop the descriptor.
    doLast {
        File(outDir, "jdk.httpserver/module-info.class").delete()
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    // SyncClient (sync/SyncClient.kt) parses JSON with org.json.JSONObject.
    // That class compiles fine against android.jar, but android.jar's
    // org.json is stub-only: at test runtime (no Robolectric in
    // SyncClientTest, deliberately — it's a plain JVM test against a real
    // HttpServer) every method throws "not mocked". org.json:json is the
    // real upstream implementation the Android SDK's version is based on;
    // adding it here puts real, working classes ahead of android.jar's
    // stubs on the test runtime classpath.
    testImplementation(libs.org.json)
}

// AGP does not wire an ad-hoc testCompileOnly(files(...)) dependency into
// KotlinCompile's actual "libraries" FileCollection for Android unit-test
// variants (confirmed empirically: it's simply absent from
// compileDebugUnitTestKotlin.libraries.files even though the dependency
// resolves fine elsewhere) — so add the jdk.httpserver stub directly to
// the task's own classpath input instead of going through the dependency
// configuration DSL.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>()
    .matching { it.name.contains("UnitTest") }
    .configureEach {
        libraries.from(jdkHttpserverStubDir.map { it.dir("jdk.httpserver") })
        dependsOn(extractJdkHttpserverStub)
    }
