plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.grimoire.extensions.lib"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(libs.grimoire.extensions.api)
}
