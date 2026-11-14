plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shottuner.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shottuner.app"
        minSdk = 24
        targetSdk = 35
        // versionCode 必须单调递增：1.5.0=12，QA 回修=13，1.6.0=15，1.7.0=16，1.7.1=17，
        // 1.8.0=18，1.9.0=19，2.0.0=20（悬浮卡重做），2.0.1=21（调透明/字号），2.1.0=22。
        // v3.0.0 是首个上架发布版（包名迁移到 com.shottuner.app + 正式签名），取 30。
        // v3.1.0 爆款化（G1 首屏任务卡 + G2 成绩水印 + G3 技法图鉴 + G4 分数历史），取 31。
        // v3.2.0 横屏可用（悬浮教练卡跟随物理方向 + 录屏画布等比例）+ 数值口径修复
        //（NumFmt 舍入顺序 / Locale 归一），取 32 —— ★ 那是**内部迭代号，从未对外发布**。
        // 对外发布版本从 1.0.0 起（clover 2026-10-29 决定：iOS 是新产品，
        // 不该沿用内部迭代号；两端统一从 1.0.0 重新对外计数）。
        // ★ versionCode **不能回退** —— 系统靠它判断能否覆盖安装，
        //   已装过 31/32 的设备装不上 versionCode 更小的包。故内部取 33。
        // 1.0.0=33，1.0.1=34（iOS 首次真正能拍），1.1.0=35（iOS 补齐四块功能），
        // 1.1.1=36（修横屏悬浮窗 + 人像/风景误判），1.1.2=37（iOS 接场景识别）。
        versionCode = 37
        versionName = "1.1.2"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    /**
     * 正式签名。
     *
     * 凭据**不进仓库**：四项全部从 `gradle.properties` 读（该文件已在 .gitignore 里）。
     *
     * 每个属性都用 `?: throw` 兜底，这不是洁癖 —— 少了它，漏配密码时 AGP 会退化成
     * "没有签名配置"，release 包要么构建失败、要么用调试密钥签出一个装不上去的包，
     * 而且这两种情况都不会在 CI 里显式报"你忘了配密码"。等到备案阶段发现签名指纹
     * 对不上，前面所有分发出去的包都要作废。
     */
    signingConfigs {
        create("release") {
            // ★ 惰性求值：四项凭据齐全才配置签名，缺任何一项**不在配置阶段抛**。
            //
            // 为什么：原来这里直接 `?: throw GradleException(...)`，
            // 而 signingConfigs 是**配置阶段**执行的 —— 于是
            // 「没有 gradle.properties」会让**所有** task 失败，
            // 连 :app:assembleDebug 和 CI 门禁（它们根本不需要签名）也一起挂。
            // CI 上 gradle.properties 被 .gitignore 排除（含明文口令），
            // 所以那正是 CI 第一次跑就会踩到的坑。
            //
            // 现在的语义等价且更准确：
            //   · 缺凭据 → 只有 assembleRelease 会失败（AGP 在那时报
            //     "SigningConfig 'release' is missing required property storeFile"），
            //     **依然是"宁可构建失败，也不要静默签出一个装不上去的包"**；
            //   · debug 构建与 CI 门禁不再被签名凭据连累。
            //
            // ★ `takeIf { it.isNotBlank() }` 不能省：
            //   gradle.properties.example 里那四项是**空字符串**（不是缺项），
            //   `as String?` 对空串照样成功 → 直接 `file("")` 会报
            //   "path may not be null or empty string" —— 那是配置阶段的错，
            //   又把 debug 构建连累上了。空串必须当成"缺"。
            fun prop(name: String): String? =
                (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() }

            val storePath = prop("RELEASE_STORE_FILE")
            val storePass = prop("RELEASE_STORE_PASSWORD")
            val keyAliasValue = prop("RELEASE_KEY_ALIAS")
            val keyPassValue = prop("RELEASE_KEY_PASSWORD")
            if (storePath != null && storePass != null && keyAliasValue != null && keyPassValue != null) {
                storeFile = file(storePath)
                storePassword = storePass
                keyAlias = keyAliasValue
                keyPassword = keyPassValue
            }
        }
    }

    buildTypes {
        release {
            // 端侧 AI 的依赖链很大（TFLite + ML Kit 管线），不压缩的话 APK 会到 43MB。
            // ML Kit 自带 consumer proguard 规则，官方支持 R8；混淆后仍需真机复测一次。
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
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
        viewBinding = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    // KMP 公共模块：app → shared **单向**依赖，shared 不依赖 app。
    // shared 里是与平台无关的算法内核（评分、构图、图鉴、技法文案），
    // iOS 版会复用同一份 commonMain。
    implementation(project(":shared"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // 端侧视觉模型：打包进 APK，离线运行。
    // 不用厂商 AI 助手，是因为没有任何一家给第三方开放「识别画面内容」的接口；
    // ML Kit 的模型自带在包里，反而能覆盖所有 Android 机型。
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:face-detection:16.1.6")

    testImplementation("junit:junit:4.13.2")
}
