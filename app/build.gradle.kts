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
        versionCode = 2
        versionName = "2.0"
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
}
