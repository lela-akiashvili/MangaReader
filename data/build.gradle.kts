plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.heavenlypillar.data"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    implementation(project(":domain"))

    // Android DocumentFile for USB/Storage access
    implementation("androidx.documentfile:documentfile:1.0.0")

    // JSON Parsing & Serialization
    implementation("com.google.code.gson:gson:2.10.1")

    // HTTP, HTML Scraping & Supabase
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}