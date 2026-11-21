plugins {
    alias(libs.plugins.android.application)
}
android {
    namespace = "dev.brainrotprevention.e2edriver"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "dev.brainrotprevention.e2edriver"
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
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.test.runner)
}
