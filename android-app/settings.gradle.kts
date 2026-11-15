pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri(providers.gradleProperty("pleosRepository").getOrElse(
                "https://nexus-playground.pleos.ai/repository/maven-releases/"
            ))
            content { includeGroup("ai.pleos.playground") }
            metadataSources { mavenPom(); ignoreGradleMetadataRedirection() }
        }
    }
}
rootProject.name = "FullChargeRoutine"
include(":app")
