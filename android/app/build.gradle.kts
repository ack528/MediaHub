import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.localtg"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.localtg"
        minSdk = 26
        targetSdk = 37
        versionCode = 49
        versionName = "2.7.0"
        // 发布版只带 arm64(真机,LSFG 原生库),调试版再加 x86_64(模拟器)
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    // 发布签名:密钥库与口令放在项目根目录 signing\(不要外传);没有该文件时 release 用调试签名,方便别人构建
    val signingProps = Properties().apply {
        val f = rootProject.file("../signing/keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (signingProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file("../signing/" + signingProps.getProperty("storeFile")) // 相对 signing\ 目录,避免中文路径被 Properties 读坏
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        debug {
            ndk { abiFilters += listOf("x86_64") }
        }
        release {
            // 个人自用,不做代码混淆 / 压缩:崩溃日志里的堆栈直接可读
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    // LSFG 帧生成的原生部分(CMake + NDK):只有运行过 tools\setup-lsfg.ps1、源码复制进来之后才启用;没有就是纯 Kotlin 工程
    ndkVersion = "27.0.12077973"
    if (file("src/main/cpp/lsfg/vendor/lsfg-vk-android/framegen/CMakeLists.txt").exists()) {
        defaultConfig {
            externalNativeBuild {
                cmake {
                    cppFlags += listOf("-std=c++20", "-fvisibility=hidden", "-fvisibility-inlines-hidden", "-ffunction-sections", "-fdata-sections")
                    arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_PLATFORM=android-29")
                }
            }
        }
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.paging:paging-compose:3.5.1")
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("me.saket.telephoto:zoomable-image-coil3:0.19.0")


    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")

    testImplementation("junit:junit:4.13.2")
}
