plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.grimoire.extension.all.libgen"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.grimoire.extension.all.libgen"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2"
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
