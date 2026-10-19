pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Nowsze wydania libsignal (po 0.86) Signal publikuje tu, nie na Maven Central.
        maven {
            name = "SignalBuildArtifacts"
            url = uri("https://build-artifacts.signal.org/libraries/maven/")
            content { includeGroupByRegex("org\\.signal.*") }
        }
    }
}
rootProject.name = "Szept"
include(":app")
