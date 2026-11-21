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
    }
}
rootProject.name = "brainrot-prevention"
include(
    ":app",
    ":core:model",
    ":core:engine",
    ":core:data",
    ":platform",
    ":testing:fixtures",
    ":e2e-driver",
    ":fixture-apps:target",
    ":fixture-apps:browser",
    ":fixture-apps:browser2",
)
