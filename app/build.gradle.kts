plugins {
    id("com.android.application")
}

android {
    namespace = "com.nfcvault"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nfcvault"
        minSdk = 23
        targetSdk = 34
        versionCode = 3
        versionName = "3.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    testImplementation(libs.junit)
}
