plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.apkintellij"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.apkintellij"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "3").toInt() + 100
        versionName = "0.2.1"
        ndk {
            // proot and the Debian rootfs are arm64 only; every DeX-capable Samsung phone is arm64.
            abiFilters += "arm64-v8a"
        }
    }

    // One fixed key, committed in the repo root. Before this, every CI run signed with a
    // freshly generated key, so each new APK refused to install over the previous one.
    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    packaging {
        // proot is executed from nativeLibraryDir, so the .so files must be extracted on install.
        jniLibs.useLegacyPackaging = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    // Galaxy Z Fold: hinge position and posture (flex mode).
    implementation("androidx.window:window:1.3.0")
    implementation("androidx.window:window-java:1.3.0")
    implementation("androidx.core:core:1.13.1")
}
