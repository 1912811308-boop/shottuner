// SPDX-License-Identifier: MIT
//
// shared —— 与平台无关的算法内核（KMP 公共模块）。
//
// 这是 Spike #0 建立的模块。探测结论（2026-10-27）：
//   · Windows 上  :shared:compileDebugKotlinAndroid            → BUILD SUCCESSFUL
//   · Linux (CI)   :shared:compileCommonMainKotlinMetadata      → BUILD SUCCESSFUL
//                    （**证明 commonMain 只用了 common API** —— 这是 iOS 移植最大的风险）
//   · macOS (CI)   :shared:compileKotlinIosSimulatorArm64      → BUILD SUCCESSFUL
//                    :shared:compileKotlinIosArm64               → BUILD SUCCESSFUL
//
// 目录约定：
//   commonMain/  跨端共享（Android + iOS 同一份实现）
//   androidMain/ 只在 Android 上编译（如 DexClock —— 用了 java.text / ThreadLocal）
//   iosMain/     只在 iOS 上编译（Swift 侧的 actual 实现）
//   commonTest/  跨端共享的单元测试（**iOS 端复用同一份**）
//
// ★ 依赖方向：**app → shared 单向**，shared 绝不依赖 app。

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
    //     从而使 `compileCommonMainKotlinMetadata` 不再被跳过 —— 单目标时 commonMain
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
        val appleTargets = listOf(iosArm64(), iosSimulatorArm64())

        // ── framework 二进制：iOS 端链接的就是它 ──────────────────────────
        //
        // ★ 为什么必须显式声明（实测踩坑，2026-10-29）：
        //   **只声明 target 是不够的** —— KGP 不会自动给 native target 建 framework。
        //   没有这个二进制，下面两个任务**根本不存在**：
        //     · :shared:linkDebugFrameworkIosSimulatorArm64（编出 Shared.framework）
        //     · :shared:embedAndSign…AppleFrameworkForXcode（Xcode 的集成入口）
        //   症状是 Xcode 的 Run Script 报
        //     "Cannot locate tasks that match ':shared:embedAndSign…'"
        //   —— 而报错里没有任何线索指向「少声明了一个二进制」，排查成本极高。
        //
        // ★ baseName = "Shared" 必须与 Swift 侧对齐：
        //   · Swift：`import Shared`
        //   · 链接：ios/project.yml 里的 OTHER_LDFLAGS = "-framework Shared"
        //   · 产物：Shared.framework
        //   改 baseName 就得把这三处一起改（ios/Scripts/kmp-build-framework.sh
        //   会自动发现 embedAndSign 任务名，不受影响）。
        //
        // ★ isStatic = false（动态 framework）：
        //   动态 framework 需要嵌进 .app/Frameworks（SideStore 侧载时会连带签名），
        //   但 Objective-C 互操作更完整 —— Kotlin/Native 出的是 ObjC 头文件，
        //   Swift 侧 `import Shared` 依赖的正是它。
        appleTargets.forEach { target ->
            target.binaries.framework {
                baseName = "Shared"
                isStatic = false
            }
        }
    }

    // ── commonTest：跨端共享的单元测试 ────────────────────────────────────
    //
    // ★ 为什么测试要进 commonTest 而不是留在 app：
    //   iOS 端要跑**同一份**测试 —— 否则 Android 绿了、iOS 红了，两边各写一套
    //   测试，等于没有共享。commonTest 里的测试在 JVM 上跑（testDebugUnitTest），
    //   也能在 iOS 模拟器上跑（iosSimulatorArm64Test）。
    //
    //   ★ kotlin("test") 不是"第三方依赖"：它是 Kotlin 官方的测试库，
    //     在 JVM 上映射到 kotlin-test-junit，在 Native 上映射到 kotlin-test。
    //     这是 KMP 官方推荐写法，不破坏"零第三方依赖"纪律。
    //
    //   ★ 迁到 commonTest 之后，`internal` 成员又可见了（internal 是**模块级**可见性，
    //     commonTest 属于 shared 模块）—— 所以 TechniqueMatcher.scoreOf
    //     当初为了留在 app 的测试而放宽成 public，现在可以改回 internal。
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
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
