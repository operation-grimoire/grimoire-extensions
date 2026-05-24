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

    // Unit tests construct a subclassed theme source directly, which transitively
    // initializes the default OkHttp client (WebViewCookieJar → android.webkit
    // .CookieManager). Returning default values for unmocked Android calls lets
    // that initializer set its field to null rather than throw — the cookie jar
    // isn't used by the page-parsing tests.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(libs.grimoire.extensions.api)
    testImplementation(libs.junit.jupiter)
    // Gradle 9 no longer auto-resolves the JUnit Platform Launcher from
    // ServiceLoader — without this dep tests are silently discovered as zero.
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
