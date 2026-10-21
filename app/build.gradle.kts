plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.litehome.bravialauncher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.litehome.aamlauncher"
        minSdk = 31
        targetSdk = 32
        versionCode = 3
        versionName = "0.3"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.4.0")
}
