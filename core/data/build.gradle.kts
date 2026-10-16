plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "com.mojaloss.core.data"
    compileSdk = 36
    defaultConfig { minSdk = 26; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core:model"))
    implementation(libs.coroutines.android)
    implementation(libs.jellyfin.core)
    // The SDK's request-time Kotlin logging references SLF4J, supplied by the host app.
    implementation("org.slf4j:slf4j-api:2.0.17")
    // Keep SDK request/response debug logging off; diagnostics use the sanitized reporter.
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp:5.3.2")
}

