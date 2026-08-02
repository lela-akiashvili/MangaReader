plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
}

android {
    namespace = "app.mangareader.mobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.mangareader.mobile"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Clean Architecture Modules
    implementation(project(":core:designsystem"))
    implementation(project(":domain"))
    implementation(project(":data"))

    // AndroidX & Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // ViewModel Compose
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Google Type-Safe Navigation Compose & Serialization [Requirement 5]
    implementation("androidx.navigation:navigation-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.10.0")
}