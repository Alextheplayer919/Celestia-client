import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // CloudburstMC's protocol snapshots (bedrock-codec/-connection/common) live
        // here — they are not published to Maven Central. Not used yet; the decode
        // work in docs/decode-research.md will need it.
        maven("https://repo.opencollab.dev/maven-snapshots")
    }
}

rootProject.name = "mc-bedrock-proxy"
include(":app")
