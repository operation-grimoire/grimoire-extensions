pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "grimoire-extensions"

include(":lib")

// Auto-include all extensions from src/{lang}/{name}/
// Module names: :en-novelfull, :zh-xxx, etc.
File(rootDir, "src")
    .walkTopDown()
    .filter { it.isDirectory && File(it, "build.gradle.kts").exists() }
    .forEach { dir ->
        val moduleName = "${dir.parentFile.name}-${dir.name}"
        include(":$moduleName")
        project(":$moduleName").projectDir = dir
    }
