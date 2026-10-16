plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.mojaloss.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.mojaloss.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.1.8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release { isMinifyEnabled = false }
    }
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(project(":core:player"))
    implementation(project(":core:downloads"))
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.window)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.compose.preview)
    implementation(libs.navigation.runtime)
    implementation(libs.navigation.ui)
    implementation(libs.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(libs.coroutines.android)
    debugImplementation(libs.compose.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation("androidx.test:runner:1.7.0")
    debugImplementation(libs.compose.test.manifest)
}
