plugins {
    // AGP 8.6.1 是首个支持 compileSdk 35 的版本；8.5.2 下把 compileSdk 改成 35 会直接构建失败。
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
