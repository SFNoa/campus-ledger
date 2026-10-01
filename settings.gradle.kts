// 仓库优先走国内镜像：本机实测 mavenCentral 只有 ~300KB/s，
// 而阿里云/腾讯镜像 ~1.4-2.5MB/s，首次构建要下近 1GB 依赖，差别很大。
// 镜像里找不到的再回落到官方源。
pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

rootProject.name = "EcardLedger"
include(":app")
