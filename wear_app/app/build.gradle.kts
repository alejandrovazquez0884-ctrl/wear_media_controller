plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

fun prop(name: String): String? = (findProperty(name) as String?)?.takeIf { it.isNotBlank() }

android {
    namespace = "io.github.wearmedia.watch"
    compileSdk = 36

    defaultConfig {
        // Must match the phone app (see gradle.properties).
        applicationId = prop("wearApplicationId") ?: error("Set wearApplicationId in gradle.properties")
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        resValue("string", "app_name", prop("wearAppName") ?: "Music")
    }

    signingConfigs {
        create("app") {
            val store = prop("wearKeystoreFile")
            if (store != null) {
                storeFile = file(store)
                storePassword = prop("wearKeystorePassword")
                keyAlias = prop("wearKeyAlias")
                keyPassword = prop("wearKeyPassword")
            } else {
                // Same as the debug key, so it pairs with a debug build of the phone app.
                val debug = getByName("debug")
                storeFile = debug.storeFile
                storePassword = debug.storePassword
                keyAlias = debug.keyAlias
                keyPassword = debug.keyPassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("app")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        resValues = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.wear.compose:compose-foundation:1.5.0")
    implementation("androidx.wear:wear-input:1.2.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
}
