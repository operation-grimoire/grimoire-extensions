plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.grimoire.extension.en.novelfull"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.grimoire.extension.en.novelfull"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "1.0.13"
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
