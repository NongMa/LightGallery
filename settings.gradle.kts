pluginManagement {
    repositories {
        google()
        // Maven Central 官方源在本网络环境不可达，优先走阿里云镜像（内容与官方一致）
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        mavenCentral()
        // jitpack 直连（阿里云 jitpack 镜像返回 401 不可用；本环境 curl jitpack.io 实测可达）
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "LightGallery"
include(":app")
