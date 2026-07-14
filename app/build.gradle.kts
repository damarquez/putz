import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
    kotlin("kapt")
}

// CONTRACT: self-update — versionCode/versionName are normally hardcoded, but the sidekick
// daemon's `build-apk` CLI command bumps update.versionCode/update.versionName in
// local.properties before triggering a build, so the app can detect "is the build in the Drive
// folder newer than what's installed." Plain Studio builds fall back to the hardcoded defaults.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.damarquez.putz"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.damarquez.putz"
        minSdk = 31
        targetSdk = 35
        versionCode = localProps.getProperty("update.versionCode")?.toIntOrNull() ?: 1
        versionName = localProps.getProperty("update.versionName") ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // CONTRACT: self-update — pinned explicitly (rather than relying on AGP's implicit
    // machine-default debug keystore) so every build — Studio's and the daemon's headless
    // `gradlew assembleDebug` — signs with the exact same certificate. A signature mismatch
    // between builds makes Android treat an update as a different app and wipe its data; see
    // debug.keystore backup at H:\My backups\debug.keystore.
    signingConfigs {
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/*.SF"
            excludes += "META-INF/*.DSA"
            excludes += "META-INF/*.RSA"
        }
    }

    // CONTRACT: self-update — distinct default filename so putz and calibreAnywhere don't
    // clobber each other in the shared Drive apk.outputDir folder.
    val apkOutputName = (project.findProperty("apk.outputName") as? String) ?: "putz-debug.apk"
    val apkOutputDir = localProps.getProperty("apk.outputDir")

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                output.outputFileName = apkOutputName
            }
        if (apkOutputDir != null) {
            variant.assembleProvider.get().doLast {
                val apkDir = variant.outputs.first().outputFile.parentFile
                copy {
                    from(apkDir)
                    into(file(apkOutputDir))
                    include(apkOutputName)
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.hilt.android)
    implementation(libs.androidx.material3)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.navigation.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.browser)
    implementation(libs.security.crypto)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    implementation(libs.play.services.auth)
    implementation(libs.google.api.services.drive)
    implementation(libs.google.api.client.android)
    implementation(libs.google.http.client.gson)
    implementation(libs.smbj)
    implementation(libs.sevenzipjbinding)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation("junit:junit:4.13.2")
}
