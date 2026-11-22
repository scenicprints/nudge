plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// An unset repo secret arrives as an empty string rather than as null, so blank means absent.
val keystorePath: String? = System.getenv("NUDGE_KEYSTORE")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.nudge.assistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nudge.assistant"
        minSdk = 31
        targetSdk = 36
        val build = System.getenv("NUDGE_BUILD")?.takeIf { it.isNotBlank() } ?: "1"
        versionCode = build.toInt()
        versionName = "0.1.$build"
    }

    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storeType = "PKCS12"
                storePassword = System.getenv("NUDGE_STORE_PASSWORD")
                keyAlias = System.getenv("NUDGE_KEY_ALIAS")
                keyPassword = System.getenv("NUDGE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without a keystore the release is signed with the debug key, so it still installs.
            signingConfig = signingConfigs.getByName(if (keystorePath != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
    }
    lint {
        // Versions are pinned to what compiles against SDK 36. Bump them together, on purpose.
        disable += listOf("GradleDependency", "NewerVersionAvailable", "UseKtx", "ObsoleteSdkInt")
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
}
