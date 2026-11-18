import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Local, gitignored — release builds are unsigned (and un-installable as an upgrade path) for
// anyone who doesn't have keystore/keystore.properties. That's expected for other contributors;
// only the release keystore's owner can produce an installable signed release APK.
val keystorePropertiesFile = rootProject.file("keystore/keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "dev.omakey.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.omakey.app"
        minSdk = 26
        targetSdk = 36
        // versionName intentionally stays fixed across iterative dev-build installs within a
        // release cycle (per user preference) — only versionCode increments, since adb install -r
        // refuses same-or-lower versionCode as a downgrade and uninstalling first would wipe local
        // app data (learned words, settings, clipboard history). Bump versionName only when
        // actually cutting a release.
        versionCode = 48
        versionName = "5.6.0"
    }

    // Two distribution channels from one codebase. `sideload` is the GitHub Releases APK and keeps
    // the in-app update check. `play` is the Google Play bundle: Play forbids an app updating
    // itself, or steering users to an APK outside Play, by any route but Play, so that build has
    // no update UI, no background worker and no POST_NOTIFICATIONS permission (declared only in
    // src/sideload/AndroidManifest.xml). SELF_UPDATE is the single switch the code reads.
    flavorDimensions += "dist"
    productFlavors {
        create("sideload") {
            dimension = "dist"
            buildConfigField("boolean", "SELF_UPDATE", "true")
        }
        create("play") {
            dimension = "dist"
            buildConfigField("boolean", "SELF_UPDATE", "false")
        }
    }

    androidResources {
        // The language model is read by memory-mapping it straight out of the APK
        // (LanguageModel.mapAsset). A compressed asset has no mappable file offset — AssetManager
        // .openFd() throws for one — so it would silently fall back to reading the whole multi-
        // megabyte model onto the heap of a process Android kills for memory. Storing it
        // uncompressed costs APK size and buys zero-copy loading.
        noCompress += "bin"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file("keystore/${keystoreProperties["storeFileName"]}")
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            // R8: shrinks and obfuscates. Play Console flags an app below 25 % obfuscation, and it
            // cuts the APK. What must survive by name is listed in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
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
        buildConfig = true
    }

    // Renames every built APK from the default "app-<buildType>.apk" to
    // "omakey-<versionName>.apk" (e.g. "omakey-2.1.0.apk") — debug and release land in separate
    // output directories (outputs/apk/debug/, outputs/apk/release/) so an identical filename
    // across build types never collides. Tracks versionName automatically, so bumping it above is
    // the only thing needed to keep this pattern going release to release.
    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            // Sideload keeps the historical name; the Play flavor is tagged so the two never mix.
            val tag = if (flavorName == "play") "-play" else ""
            output.outputFileName = "omakey$tag-${versionName}.apk"
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":extensions-api"))
    implementation(project(":extensions-builtin"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.savedstate.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // Only the sideload flavor runs the periodic update check; Play has no WorkManager at all.
    "sideloadImplementation"(libs.androidx.work.runtime.ktx)
    // Inline autofill's style bundle (UiVersions / InlineSuggestionUi): the format password managers read.
    implementation(libs.androidx.autofill)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}

// With product flavors AGP no longer creates plain `compileDebugKotlin` / `testDebugUnitTest`.
// CI, AGENTS.md and the Stop hook all call those names, so keep them as aggregates that cover both
// distribution flavors — a change that breaks only the Play build must still fail the check.
tasks.register("compileDebugKotlin") {
    dependsOn("compileSideloadDebugKotlin", "compilePlayDebugKotlin")
}
tasks.register("testDebugUnitTest") {
    dependsOn("testSideloadDebugUnitTest", "testPlayDebugUnitTest")
}
