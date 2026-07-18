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
    }

    buildFeatures {
        compose = true
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
}
