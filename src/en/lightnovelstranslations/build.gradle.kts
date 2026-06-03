plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.grimoire.extension.en.lightnovelstranslations"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.grimoire.extension.en.lightnovelstranslations"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "1.0.6"
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

    // Unit tests parse captured HTML with Jsoup; nothing actually touches
    // Android APIs, but the source constructor transitively initializes the
    // default OkHttp client (which reaches into android.webkit). Returning
    // default values lets that initializer set its field to null rather than
    // throw — same workaround Foxaholic's test setup uses.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":lib"))
    testImplementation(libs.junit.jupiter)
    // Gradle 9 no longer auto-resolves the JUnit Platform Launcher from
    // ServiceLoader — without this dep tests are silently discovered as zero.
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
