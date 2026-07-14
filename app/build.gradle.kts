import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
    kotlin("kapt")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

// CONTRACT: self-update — versionCode is the build's Unix timestamp (seconds), so every build
// (Studio's or the daemon's) is automatically newer than the last with no manual bump anywhere.
// The daemon's LAN /api/putz/version endpoint reports the live build output APK's mtime, which
// the app compares directly against BuildConfig.VERSION_CODE — same "epoch seconds" currency.
val buildTimestamp = (System.currentTimeMillis() / 1000).toInt()

android {
    namespace = "com.damarquez.putz"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.damarquez.putz"
        minSdk = 31
        targetSdk = 35
        versionCode = buildTimestamp
        versionName = SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())

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
    // CONTRACT: self-update — the sidekick daemon runs as a Docker container on the NAS (see
    // 4_deploy_to_nas.bat), with no visibility into this PC's Gradle build output, so the LAN
    // self-update path needs its own delivery copy here (Drive above remains an independent
    // fallback). Must match the daemon's apk_lan_delivery_path (sidekick.config.json) — see
    // app_updater.py's _DEFAULT_APK_LAN_DELIVERY_PATH.
    val apkLanOutputDir = localProps.getProperty("apk.lanOutputDir")

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                output.outputFileName = apkOutputName
            }
        variant.assembleProvider.get().doLast {
            val apkDir = variant.outputs.first().outputFile.parentFile
            // CONTRACT: self-update — a small sidecar file holding the exact same integer as
            // this build's versionCode. The daemon's LAN endpoint (and, via Drive sync, the
            // Drive fallback) reports THIS number rather than the APK file's mtime — mtime is
            // stamped when the file finishes being written (after compile/package/copy, minutes
            // after configuration time), so comparing it against BuildConfig.VERSION_CODE (which
            // is fixed at configuration time) made even the just-installed build look "older"
            // than itself and update-checks would never settle on "up to date".
            val versionSidecar = File(apkDir, "$apkOutputName.version")
            versionSidecar.writeText(buildTimestamp.toString())
            if (apkOutputDir != null) {
                copy {
                    from(apkDir)
                    into(file(apkOutputDir))
                    include(apkOutputName, "$apkOutputName.version")
                }
            }
            if (apkLanOutputDir != null) {
                // CONTRACT: self-update — best-effort: an unreachable NAS/network must never
                // fail an otherwise-successful build (unlike apkOutputDir's Drive-desktop-backed
                // drive letter, this is a raw UNC path with no local caching layer).
                try {
                    copy {
                        from(apkDir)
                        into(file(apkLanOutputDir))
                        include(apkOutputName, "$apkOutputName.version")
                    }
                } catch (e: Exception) {
                    project.logger.warn("self-update: could not deliver APK to apk.lanOutputDir ($apkLanOutputDir): ${e.message}")
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
