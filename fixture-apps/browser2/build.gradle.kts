plugins {
    alias(libs.plugins.android.application)
}
android {
    namespace = "dev.brainrotprevention.fixture.browser2"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "dev.brainrotprevention.fixture.browser2"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
}
