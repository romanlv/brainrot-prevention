plugins {
    alias(libs.plugins.android.library)
}
android {
    namespace = "dev.brainrotprevention.testing"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    buildToolsVersion = "37.0.0"
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    api(project(":core:engine"))
    // G3 contract (scripts/e2e/TAGS.md): @Milestone, @NoGrant and MilestoneFilter for the androidTest APKs.
    api(libs.junit)
    implementation(libs.androidx.test.runner)
}
