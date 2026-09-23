plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CloudburstMC protocol version, pinned to one timestamped snapshot.
val cloudburstVersion = "3.0.0.Beta13-20260921.100211-29"

android {
    namespace = "com.proxy.mcbedrock"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.proxy.mcbedrock"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.4-hud"
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

    // Netty and the CloudburstMC jars each ship their own licence/notice files and
    // an INDEX.LIST; without these exclusions the APK packaging step fails on
    // duplicates.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/io.netty.versions.properties",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
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

    // Bedrock protocol: needed from here on, because a terminated session has to
    // encode and decode packets rather than copy bytes. Pinned to a timestamped
    // snapshot so builds are reproducible (see docs/decode-research.md).
    implementation("org.cloudburstmc.protocol:bedrock-codec:$cloudburstVersion")
    implementation("org.cloudburstmc.protocol:bedrock-connection:$cloudburstVersion")

    testImplementation("junit:junit:4.13.2")
}
