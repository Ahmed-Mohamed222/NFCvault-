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
        versionCode = 6
        versionName = "3.1.2"
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
