import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.morchid.ecardledger"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.morchid.ecardledger"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        vectorDrawables { useSupportLibrary = true }
        // 真机/模拟器上的 instrumented 测试（用于覆盖 Robolectric 跑不了的对话框场景）
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // 口令从 keystore.properties 读 —— 那个文件在 .gitignore 里，**不会进版本库**。
            // （原来明文写在构建脚本里：一旦推到 GitHub，等于把签名密钥连同口令一起送出去，
            //   别人就能签出和你一模一样的更新包。）
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) {
                val props = Properties()
                propsFile.inputStream().use { stream -> props.load(stream) }
                storeFile = rootProject.file(props.getProperty("storeFile", "keystore/ledger-release.jks"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias", "ledger")
                keyPassword = props.getProperty("keyPassword")
            } else {
                // 没有配置文件就不签名（release 包会是未签名的，装不上；不会因为缺文件而整个构建失败）
                println("⚠️ 没找到 keystore.properties，release 包将不会签名")
            }
        }
    }

    buildTypes {
        release {
            // R8：代码压缩 + 资源压缩。
            // debug 包没开这些，Compose 也是未优化版本 —— 真机反馈的"滑动掉帧"很大一部分来自这里。
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // Robolectric 需要能读到合并后的资源与 manifest
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 微信导出的账单是**加密 zip**（密码由微信单独发给你），
    // java.util.zip 不支持加密压缩包，所以这里需要一个专门的库。
    implementation("net.lingala.zip4j:zip4j:2.11.5")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // 单元测试里用真正的 org.json（android.jar 里的是会抛异常的桩），
    // 这样就能在 JVM 上直接跑生产解析代码，包括真实联网的 EcardLiveIT
    testImplementation("org.json:json:20260814")
    // 在 JVM 上跑 Android 框架代码：SQLite、Context、以及 Compose 的无头渲染
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // instrumented 测试（跑在真机/模拟器上）：gradle connectedDebugAndroidTest
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

// 让 println / 断言日志出现在 gradle 输出里
tasks.withType<Test>().configureEach {
    // Robolectric + Compose 很吃内存：40+ 个测试跑在同一个 JVM 里，
    // 默认堆会在后面的用例上 OOM（实测踩到过）
    maxHeapSize = "3g"
    // Robolectric 跑多个 Compose 测试时，同一个 JVM 里会互相污染，
    // 后面的用例会报 "Compose did not get idle"（已知问题：
    // https://github.com/robolectric/robolectric/issues/7055
    // https://stackoverflow.com/questions/79608556 ）。
    // 每个测试类换一个干净 JVM 是官方建议的规避方式。
    forkEvery = 1
    maxParallelForks = 1
    // Robolectric 运行时要下载 android-all jar，走国内镜像（官方源太慢）
    systemProperty("robolectric.dependency.repo.url", "https://maven.aliyun.com/repository/public")
    systemProperty("robolectric.dependency.repo.id", "aliyun")
    // JDK 17 下 Robolectric 反射访问 java.base 需要放开
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
    )
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

/**
 * 把项目根目录的 TERMS.md / PRIVACY.md 同步进 assets，供 App 内离线查看。
 *
 * 为什么用构建任务而不是手动复制：**两份文件迟早会写歪**。
 * 根目录那两份是唯一事实来源（GitHub 上展示的也是它们），
 * 这里每次构建自动同步，assets 里的副本不需要提交。
 */
val syncLegalDocs by tasks.registering(Copy::class) {
    from(rootProject.file("TERMS.md"), rootProject.file("PRIVACY.md"))
    into(layout.projectDirectory.dir("src/main/assets/legal"))
}

tasks.named("preBuild") { dependsOn(syncLegalDocs) }