plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.aly.sahlconnect"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.aly.sahlconnect"
        minSdk = 26
        targetSdk = 34
        versionCode = 1 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionName = "0.1-preview"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":common"))
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
