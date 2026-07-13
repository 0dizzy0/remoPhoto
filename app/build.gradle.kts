import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val releaseSigningPropertiesFile = rootProject.file(".signing/signing.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.isFile) {
        releaseSigningPropertiesFile.inputStream().use { load(it) }
    }
}
val releaseStoreFilePath =
    System.getenv("REMOPHOTO_RELEASE_STORE_FILE")
        ?: releaseSigningProperties.getProperty("storeFile")
val releaseStorePassword =
    System.getenv("REMOPHOTO_RELEASE_STORE_PASSWORD")
        ?: releaseSigningProperties.getProperty("storePassword")
val releaseKeyAlias =
    System.getenv("REMOPHOTO_RELEASE_KEY_ALIAS")
        ?: releaseSigningProperties.getProperty("keyAlias")
val releaseKeyPassword =
    System.getenv("REMOPHOTO_RELEASE_KEY_PASSWORD")
        ?: releaseSigningProperties.getProperty("keyPassword")
val releaseSigningValues = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
)
val releaseSigningConfigured = releaseSigningValues.all { !it.isNullOrBlank() }
val releaseSigningRequested =
    releaseSigningPropertiesFile.isFile || releaseSigningValues.any { !it.isNullOrBlank() }

if (releaseSigningRequested && !releaseSigningConfigured) {
    throw GradleException(
        "Release signing 配置不完整：请检查 .signing/signing.properties 或 REMOPHOTO_RELEASE_* 环境变量"
    )
}

android {
    namespace = "com.remophoto"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.remophoto"
        minSdk = 29
        targetSdk = 35
        versionCode = 6
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                val configuredStoreFile = rootProject.file(checkNotNull(releaseStoreFilePath))
                if (!configuredStoreFile.isFile) {
                    throw GradleException("Release keystore 不存在：$configuredStoreFile")
                }
                storeFile = configuredStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            // 与公开签名版本并存，避免本地调试因签名不同覆盖或卸载用户数据。
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
        buildConfig = true
        compose = true
    }
}

ksp {
    // Room schema 是数据库迁移的版本基线，必须纳入版本控制。
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Kotlin & AndroidX Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Lifecycle + ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // 长时间扫描：前台 WorkManager 在息屏、切后台和进程重建后继续调度
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Room (KSP for Kotlin 2.0+)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Coil — 图片加载 + GIF 支持
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // DocumentFile — SAF 文件访问
    implementation("androidx.documentfile:documentfile:1.0.1")

    // DataStore — 偏好设置持久化
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Phase 4: NanoHTTPd — 轻量 HTTP Server（远程仓库服务端）
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Phase 4: JmDNS — mDNS 服务注册与发现
    implementation("org.jmdns:jmdns:3.5.9")

    // SMB2/3 只读客户端；Android/R8 兼容性已在独立 Spike 中验证。
    implementation("com.hierynomus:smbj:0.14.0")
    constraints {
        implementation("org.bouncycastle:bcprov-jdk18on:1.84") {
            because("CVE-2026-0636 affects bcprov-jdk18on 1.74 through 1.83")
        }
    }

    // SplashScreen
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
