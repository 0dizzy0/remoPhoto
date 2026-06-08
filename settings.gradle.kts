pluginManagement {
    repositories {
        // Google 官方仓库放首位，确保 Gradle 插件（AGP/Kotlin/KSP）能下载到最新版
        google()
        // 阿里云镜像作为国内加速备选
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 阿里云镜像优先（依赖下载加速），谷歌官方作为备选
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        google()
        mavenCentral()
    }
}

rootProject.name = "remoPhoto"
include(":app")
