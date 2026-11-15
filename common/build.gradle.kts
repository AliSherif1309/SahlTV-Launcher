plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

dependencies { api(project(":connect-protocol")) }

// The Sahl family: one look (colours, Thmanyah fonts, dialogs), the phone upload page, storage helpers and
// network safety checks, shared by Sahl TV, Sahl Quran, Sahl PDF and Sahl Files. No external libraries.
android {
    namespace = "com.aly.sahl.common"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
