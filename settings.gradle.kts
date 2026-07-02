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

val isCiEnvironment = System.getenv("CI").equals("true", ignoreCase = true)
println(
    "[repository-config] environment=${if (isCiEnvironment) "ci" else "local"}, " +
        "dependencyOrder=${if (isCiEnvironment) "official-first" else "china-mirror-first"}",
)

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (isCiEnvironment) {
            // GitHub Runner 直接使用官方源，避免国内镜像的跨境瞬时 5xx 阻断回退。
            google()
            mavenCentral()
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        } else {
            // 国内开发环境优先使用镜像；AndroidX 位于 google 镜像，必须排在 public 前。
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            google()
            mavenCentral()
        }
    }
}

rootProject.name = "remoPhoto"
include(":app")
