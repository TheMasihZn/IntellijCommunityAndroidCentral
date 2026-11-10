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
        versionCode = 2
        versionName = "0.2.0"
        ndk {
            // proot and the Debian rootfs are arm64 only; every DeX-capable Samsung phone is arm64.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the APK installs without extra setup.
            signingConfig = signingConfigs.getByName("debug")
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
