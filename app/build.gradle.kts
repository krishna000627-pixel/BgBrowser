import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing values come from keystore.properties (local) or env vars (GitHub Actions).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signing(prop: String, env: String): String? =
    keystoreProps.getProperty(prop) ?: System.getenv(env)

val ksPath = signing("storeFile", "KEYSTORE_PATH")
val ksPass = signing("storePassword", "KEYSTORE_PASSWORD")
val ksAlias = signing("keyAlias", "KEY_ALIAS")
val ksKeyPass = signing("keyPassword", "KEY_PASSWORD")
val hasReleaseKey = ksPath != null && ksPass != null && ksAlias != null && ksKeyPass != null &&
    rootProject.file(ksPath).exists()

android {
    namespace = "com.bgbrowser"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.bgbrowser"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "11.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(ksPath!!)
                storePassword = ksPass
                keyAlias = ksAlias
                keyPassword = ksKeyPass
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
    // Tor (Guardian Project), pinned. Driven by reflection in TorManager.kt.
    implementation("info.guardianproject:tor-android:0.4.8.17.2")
    implementation("info.guardianproject:jtorctl:0.4.5.7")
}
