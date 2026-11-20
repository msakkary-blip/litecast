plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNo = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val keystore = file("litecast.jks")

android {
    namespace = "com.msakkary.litecast"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.msakkary.litecast"
        minSdk = 29
        targetSdk = 34
        versionCode = runNo
        versionName = "1.0.$runNo"
    }

    signingConfigs {
        create("release") {
            if (keystore.exists()) {
                storeFile = keystore
                storePassword = System.getenv("KS_PASS") ?: "litecast"
                keyAlias = "litecast"
                keyPassword = System.getenv("KS_PASS") ?: "litecast"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystore.exists()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
}
