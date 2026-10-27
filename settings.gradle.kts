pluginManagement {
    repositories {
        google()
        mavenCentral()
        // No gradlePluginPortal(): every plugin used here (AGP, Kotlin, Compose)
        // publishes its marker artifact to Google Maven / Maven Central, and the
        // sandbox egress proxy resets Gradle's connections to plugins.gradle.org.
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AstroExposure"

include(":domain", ":data", ":app")
