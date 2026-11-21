plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "dev.brainrotprevention"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "dev.brainrotprevention"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro", "proguard-strip-log.pro")
        }
        create("qa") {
            initWith(getByName("release"))
            applicationIdSuffix = ".qa"
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles.clear()
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            matchingFallbacks += listOf("release")
        }
    }
    sourceSets {
        getByName("debug") {
            java.directories += "src/hooks/kotlin"
            kotlin.directories += "src/hooks/kotlin"
            assets.directories += "src/hooks/assets"
        }
        getByName("qa") {
            java.directories += "src/hooks/kotlin"
            kotlin.directories += "src/hooks/kotlin"
            assets.directories += "src/hooks/assets"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        baseline = file("lint-baseline.xml")
        error += listOf("MissingPermission", "ExportedReceiver", "UnspecifiedImmutableFlag")
        abortOnError = true
        checkReleaseBuilds = false
    }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}
dependencies {
    implementation(project(":platform"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.espresso.intents)
    androidTestImplementation(libs.androidx.espresso.core)
    testImplementation(libs.junit)
}
