plugins {
    id("com.android.application")
}

android {
    namespace = "com.aym.sports"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aym.sports"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
