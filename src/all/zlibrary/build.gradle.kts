plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.grimoire.extension.en.zlibrary"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.grimoire.extension.en.zlibrary"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "1.0.9"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":lib"))
}
