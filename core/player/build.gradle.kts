plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "com.mojaloss.core.player"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core:model"))
    implementation(libs.coroutines.android)
    api(libs.media3.exoplayer)
    api(libs.media3.ui)
    implementation(libs.media3.hls)
    implementation(libs.media3.dash)
    implementation(libs.media3.okhttp)
    testImplementation(libs.junit)
}

