pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ShotTuner"
include(":app")
// shared：KMP 公共模块。app → shared 单向依赖，shared 不依赖 app。
include(":shared")
