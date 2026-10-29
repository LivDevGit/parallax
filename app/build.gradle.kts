import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Personal keys live in the git-ignored local.properties (or env vars on CI), never in the repo.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val tmdbToken = localProperties.getProperty("tmdb.token") ?: System.getenv("TMDB_TOKEN") ?: ""

android {
    namespace = "com.parallax.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.parallax.launcher"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // TMDB "API Read Access Token". Without it the Top Shelf just shows the blurred app banner.
        buildConfigField("String", "TMDB_TOKEN", "\"$tmdbToken\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Placeholder until a real release key exists; lets local release builds install over debug ones.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.07.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    // Installs Compose's bundled baseline profiles so the UI is precompiled instead of interpreted.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}
