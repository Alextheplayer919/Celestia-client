plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.proxy.mcbedrock"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.proxy.mcbedrock"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.3-panel"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Material 3 components for the control panel. Pinned at 1.12.0: it is the
    // newest release that builds against compileSdk 34 (1.13/1.14 require a newer
    // compileSdk than this project uses).
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Bedrock protocol decoding (read-only) is not wired in yet. When it is, the
    // coordinates are org.cloudburstmc.protocol:{common,bedrock-codec,bedrock-connection}
    // from https://repo.opencollab.dev/maven-snapshots — see docs/decode-research.md
    // for the exact pinned version and why a decoder alone cannot read an encrypted
    // session.
    // implementation("org.cloudburstmc.protocol:bedrock-codec:VERSION")

    testImplementation("junit:junit:4.13.2")
}
