plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.localtg.probe"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.localtg.probe"
        minSdk = 29
        targetSdk = 37
        versionCode = 16
        versionName = "0.9.7"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-fvisibility=hidden", "-ffunction-sections", "-fdata-sections")
                arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_PLATFORM=android-29")
            }
        }
    }
    // 自用检测程序:release 也用调试签名,直接 adb install / 手机上安装
    buildTypes {
        debug {
            ndk { abiFilters += listOf("x86_64") } // 模拟器自测用
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    ndkVersion = "27.0.12077973"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // 视频不要再压缩,MediaExtractor 直接读
    androidResources { noCompress += listOf("mp4") }
}

// 回放测试要用主程序真实的渲染管线(ExoPlayer → VideoRenderer → LSFG → 上屏),直接把主程序的 render 包和 AppLog 复制进来一起编译,
// 这样检测程序测到的就是主程序实际跑的代码,不会各改各的。
val syncMainRender = tasks.register<Copy>("syncMainRender") {
    from("../../android/app/src/main/java/com/localtg") {
        include("render/**", "AppLog.kt")
        exclude("render/HardwareProbe.kt")
    }
    into(layout.buildDirectory.dir("generated/mainsrc/com/localtg"))
}
android.sourceSets.getByName("main").kotlin.directories.add(layout.buildDirectory.dir("generated/mainsrc").get().asFile.path)
tasks.matching { it.name == "preBuild" || (it.name.startsWith("compile") && it.name.endsWith("Kotlin")) }.configureEach { dependsOn(syncMainRender) }

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
}
