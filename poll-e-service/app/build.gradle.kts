plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.termux.suggest"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.termux.suggest"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("termux") {
            storeFile = file(project.findProperty("TERMUX_KEYSTORE") as String)
            storePassword = project.findProperty("TERMUX_STORE_PASSWORD") as String
            keyAlias = project.findProperty("TERMUX_KEY_ALIAS") as String
            keyPassword = project.findProperty("TERMUX_KEY_PASSWORD") as String
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("termux")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
