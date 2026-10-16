plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "com.mojaloss.core.downloads"
    compileSdk = 36
    defaultConfig { minSdk = 26; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core:model"))
    implementation(libs.coroutines.android)
    api(libs.media3.exoplayer)
    api(libs.media3.datasource)
    implementation(libs.media3.database)
    implementation(libs.media3.okhttp)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
}
