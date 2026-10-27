plugins {
    // AGP 8.6.1 是首个支持 compileSdk 35 的版本；8.5.2 下把 compileSdk 改成 35 会直接构建失败。
    id("com.android.application") version "8.6.1" apply false
    id("com.android.library") version "8.6.1" apply false
    // KMP 插件版本必须与 Android 插件严格一致：
    // 同一构建内 Kotlin Gradle Plugin 版本不一致会被 KGP 直接判为错误
    //（"The Kotlin Gradle plugin was loaded multiple times"）。
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.multiplatform") version "1.9.24" apply false
}
