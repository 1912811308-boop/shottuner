// SPDX-License-Identifier: MIT
//
// 最小探测模块（Spike #0）。
//
// 本文件只验证一件事：**commonMain 的 metadata 编译能否在本机跑通**。
// 探测通过之前，不往 shared 里搬任何业务代码。

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    // Android 目标：本机可编译验证（Windows 上唯一能真正产出二进制的目标）。
    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                kotlinOptions.jvmTarget = "17"
            }
        }
    }

    // iOS 目标：**由 gradle 属性开关控制**，默认关闭。
    //
    // Kotlin/Native 的宿主限制是硬约束：Apple 目标只能从 macOS 宿主编译。
    // Windows 宿主上这两个目标会被 KGP 自动标记为 disabled，编译任务不会执行。
    //
    // 但"声明"本身有意义：
    //  1. 它让 commonMain 真正成为**多目标共享源码集**，
    //     从而使 `compileKotlinMetadata` 不再被跳过 —— 单目标时 commonMain
    //     等价于 androidMain，metadata 编译会直接 SKIPPED，探测就是假的。
    //  2. 它把"未来在 macOS 上要编的产物"钉进构建脚本，
    //     避免日后有人以为 iOS 支持是拍脑袋加的。
    //
    // ★ 为什么加开关（实测踩坑）：
    //   只要声明了 Apple 目标，KGP 就会去解析 `kotlin-native-prebuilt-<host>` 依赖 ——
    //   **哪怕在 Windows 上、哪怕该目标已被 disabled**。
    //   于是 Windows 开发者为了改一行 commonMain，也得先下载几百 MB 的
    //   Kotlin/Native 预编译包，而且还得先把 settings 的仓库模式改对
    //   （PREFER_SETTINGS 会忽略 KGP 自动加的 native 仓库 → 解析失败）。
    //   代价与收益完全不成比例。
    //
    //   所以：**默认关**（Windows / 普通 Linux 本地开发零负担），
    //   **CI 上用 -PenableAppleTargets=true 打开**（那里本来就要编 iOS 产物）。
    if (providers.gradleProperty("enableAppleTargets").orNull == "true") {
        iosArm64()
        iosSimulatorArm64()
    }
}

android {
    // 只影响本模块生成的 R/BuildConfig，与 Kotlin 包名无关；
    // 显式与 app 模块的 com.shottuner.app 错开，避免资源 ID 冲突。
    namespace = "com.shottuner.app.shared"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// 硬约束：不引入任何第三方依赖。
// KMP 插件本身不进入 dependencies 块；commonMain 只依赖 kotlin stdlib。
dependencies {
}
