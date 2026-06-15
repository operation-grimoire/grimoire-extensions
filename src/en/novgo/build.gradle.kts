plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.grimoire.extension.en.novgo"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.grimoire.extension.en.novgo"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "1.0.10"
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
