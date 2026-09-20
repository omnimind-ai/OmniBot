pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "OmniInferPhone"
include(":app", ":omniinfer-server")
project(":omniinfer-server").projectDir = file(providers.gradleProperty("omniinfer.source").get() + "/android/omniinfer-server")
