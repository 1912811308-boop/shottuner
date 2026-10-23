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
        versionCode = 31
        versionName = "3.1.0"
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
            storeFile = file(
                (project.findProperty("RELEASE_STORE_FILE") as String?)
                    ?: throw GradleException("缺少 RELEASE_STORE_FILE：请检查 gradle.properties")
            )
            storePassword = (project.findProperty("RELEASE_STORE_PASSWORD") as String?)
                ?: throw GradleException("缺少 RELEASE_STORE_PASSWORD：请检查 gradle.properties")
            keyAlias = (project.findProperty("RELEASE_KEY_ALIAS") as String?)
                ?: throw GradleException("缺少 RELEASE_KEY_ALIAS：请检查 gradle.properties")
            keyPassword = (project.findProperty("RELEASE_KEY_PASSWORD") as String?)
                ?: throw GradleException("缺少 RELEASE_KEY_PASSWORD：请检查 gradle.properties")
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
