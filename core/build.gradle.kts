plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.omakey.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            all {
                // Gradle's own -D flags land on the daemon, not on the forked test JVM, so the
                // evaluation harness's opt-in switches have to be forwarded explicitly:
                //   -Domakey.tune=true       runs the parameter sweep (EngineTuningTest)
                //   -Domakey.eval.full=true  scores the full corpora instead of a sample
                it.systemProperty("omakey.tune", System.getProperty("omakey.tune") ?: "")
                it.systemProperty("omakey.eval.full", System.getProperty("omakey.eval.full") ?: "")
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.ui)
    api(libs.androidx.material3)
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// Room writes the schema of every version here, and it is committed, so a schema change shows up
// as a reviewable diff instead of happening silently. exportSchema was off until v4, so there are
// no JSON schemas for v1-v3 and none can be recovered — which is why OmakeyDatabaseMigrationTest
// builds the old database by hand. From v4 onward the real schemas exist, so a future 4->5 can use
// Room's MigrationTestHelper instead; add `androidx-room-testing` at that point rather than
// carrying an unused dependency until then.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
