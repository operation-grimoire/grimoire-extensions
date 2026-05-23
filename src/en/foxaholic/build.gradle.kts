plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.grimoire.extension.en.foxaholic"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.grimoire.extension.en.foxaholic"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.0.4"
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

    // Unit tests construct the Foxaholic source directly, which transitively
    // initializes the default OkHttp client (WebViewCookieJar →
    // android.webkit.CookieManager). Returning default values for unmocked
    // Android calls lets that initializer set its field to null rather than
    // throw — the cookie jar isn't actually used by the parsing tests.
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
