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
        versionCode = 1
        versionName = "0.1-skeleton"
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Bedrock RakNet + game protocol decoding (read-only packet parsing).
    // TODO: pin an actual released version once you're wiring in real decoding —
    // check https://github.com/CloudburstMC/Protocol for current coordinates.
    // implementation("org.cloudburstmc.protocol:bedrock-codec:VERSION")
    // implementation("org.cloudburstmc.protocol:bedrock-connection:VERSION")
}
