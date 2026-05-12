plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
}

// Always re-resolve SNAPSHOT dependencies. Without this, Gradle caches
// changing modules for 24h and CI runs that restore ~/.gradle/caches
// happily link against the previously-published SNAPSHOT even after a
// fresh API publish.
allprojects {
    configurations.all {
        resolutionStrategy.cacheChangingModulesFor(0, "seconds")
    }
}
