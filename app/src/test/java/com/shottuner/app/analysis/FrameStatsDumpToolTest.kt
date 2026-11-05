package com.shottuner.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * **手动工具**：把 `docs/fixtures/frame-stats/` 里每一张 PNG 跑成 FrameStats 文本快照。
 *
 * ★★ 这不是回归测试。类级 `@Ignore` 让它永远不参与常规 `gradle test`；
 *    要跑的时候手工去掉 `@Ignore`，跑完**务必加回去**。
 *    名字刻意不叫 `*BaselineDumpTest` —— `3e12fa5` 删掉的那个临时基线采集器
 *    就是因为「攒着做、被打断、什么都没留下」而消失的；这里用 `Tool` 命名，
 *    一眼能看出是工具而不是门禁。
 *
 * ## 它解决什么问题
 *
 * T12 的验收①是「同一批固定图 → Android 与 iOS 各出一份统计量 → 逐字段 diff」。
 * iOS 侧 `ios/ShotTuner/Camera/FrameStatsProducer.swift` 已经能出
 * `<label>.ios.dump.txt`；Android 侧一直没有对应产物，于是验收永远停在
 * 「一端有文件、另一端没东西可比」。这个工具补的就是这一步。
 *
 * ## 为什么文本不是这里拼的
 *
 * 拼文本的逻辑在 commonMain 的 [FrameStatsDump.computeDump]：两端调**同一份**
 * Kotlin 代码，格式一致是结构保证。本工具只做四件事：
 * 解 PNG → RGB → I420 → 交给 `computeDump` → 原样落盘。
 * 一旦这里也开始拼字符串，Float 的格式化就成了第二个变量，diff 永远对不上。
 *
 * ## RGB → I420 与解码都是**复用**，不是重写
 *
 * - 解码：[PngDecoder.decode]（`app/src/main/java/com/shottuner/app/analysis/PngDecoder.kt:46`）。
 * - RGB→I420：[RgbToYuv.toI420]（`app/src/main/java/com/shottuner/app/analysis/YuvImage.kt:92`），
 *   输入是 [DecodedImage]（ARGB_8888 像素数组）。
 *
 * 所以**没有第二套 BT.601 系数**，也没有第二个 PNG 解码实现。
 * 将来 `RgbToYuv` 的系数或量化规则变了，本工具自动跟着变 ——
 * 这正是「复用」和「照抄」的区别；跨端 diff 比的必须是 App 真正在用的那条链路。
 *
 * ## ⚠️ 为什么没用 `javax.imageio.ImageIO`（与最初设想不同，原因已实测）
 *
 * 原计划用 JDK 的 `ImageIO` 读 PNG。实测编译失败：
 *
 * ```text
 * e: FrameStatsDumpToolTest.kt:11:14 Unresolved reference: imageio
 * e: FrameStatsDumpToolTest.kt:120:19 Unresolved reference: writeString
 * ```
 *
 * 原因是 **AGP 给 `app` 的单元测试编译用的是 `android.jar` 的 API 面（不是完整
 * JDK）**：`android.jar` 里没有 `java.desktop` 模块，所以 `javax.imageio` 根本不存在；
 * `java.nio.file.Files` 也是 Android 的桩版本，没有 `Files.writeString`（Java 11+）。
 * 这不是「JVM 单测不能用 JDK 类」的普遍结论，而是**本工程的编译配置决定的事实**，
 * 与其去改 `app/build.gradle.kts` 的编译参数，不如用仓库自己的解码器：
 *
 * 1. [PngDecoder] 的 KDoc 第一条理由就是「可测：不用 BitmapFactory，纯 JVM 单测能跑」，
 *    它存在的目的与本工具完全一致；
 * 2. PNG 是无损格式，[PngDecoder] 解出来的就是文件里的原始像素，
 *    与 iOS 侧 CoreGraphics 解出来的应当逐字节相同 —— 跨端比对因此连
 *    「解码器差异」这一项都排除掉了；
 * 3. 它**就是 Android App 真正在用的那个解码器**，diff 比的是真实链路。
 *
 * 同理落盘不用 `Files.writeString`：改用 `String.toByteArray(StandardCharsets.UTF_8)`
 * + `File.writeBytes(...)`。**语义完全一样**（显式 UTF-8、不写 BOM），
 * 只是绕开 `android.jar` 里缺的那个方法。**绝不用 `FileWriter`** ——
 * 它吃平台默认编码，中文 Windows 上默认是 GBK，写出来 iOS 侧读不了。
 *
 * ## 怎么跑（Windows / PowerShell）
 *
 * ```powershell
 * # 1) 临时去掉类上的 @Ignore（只删那一行注解）
 * # 2) 跑单类
 * .\gradlew.bat :app:testDebugUnitTest --tests "*FrameStatsDumpToolTest"
 * # 3) 产物在 <仓库根>/build/frame-stats-dump/android/
 * # 4) 把 @Ignore 加回去再提交
 * ```
 *
 * 与 iOS 侧 diff：
 * ```bash
 * diff -r build/frame-stats-dump/android build/frame-stats-dump/ios
 * ```
 *
 * ## 输出约定（与 iOS 侧逐字对齐，任何一条不同都会造成假差异）
 *
 * - 文件名：`<label>.android.dump.txt`（iOS 是 `<label>.ios.dump.txt`），
 *   去掉 `.android` / `.ios` 段后可直接配对比较；
 * - `label` = `fixture.name`，**含 `.png` 后缀**（与 iOS 侧 `lastPathComponent` 同源）；
 * - 编码：UTF-8 **无 BOM**；
 * - 换行：只用 `\n`（[FrameStatsDump] 自己写死 LF，这里不做任何转换）；
 * - 顺序：按文件名升序，与 iOS 的 `sortedBy { lastPathComponent }` 一致。
 */
@Ignore("手动工具：需要 fixture 图集，不参与常规测试")
class FrameStatsDumpToolTest {

    @Test
    fun dumpAllFixtures() {
        val fixturesDir = locateFixturesDir()
        // docs/fixtures/frame-stats → 上三级是仓库根。Java 的 getParentFile() 是 @Nullable，
        // 所以逐级安全调用；层级不对时明确报错，而不是让 NPE 糊过去。
        val repoRoot = fixturesDir.parentFile?.parentFile?.parentFile
            ?: error("图集目录层级异常（上溯三级拿不到仓库根）：${fixturesDir.absolutePath}")
        val outputDir = File(repoRoot, "build/frame-stats-dump/android")
        outputDir.mkdirs()

        val fixtures = fixturesDir
            .listFiles { f -> f.isFile && f.extension.lowercase() == "png" }
            ?.sortedBy { it.name }
            ?: error("图集目录不可读：${fixturesDir.absolutePath}")
        assertTrue("图集目录里没有 PNG：${fixturesDir.absolutePath}", fixtures.isNotEmpty())

        println("fixtures : ${fixturesDir.absolutePath}")
        println("output   : ${outputDir.absolutePath}")

        for (fixture in fixtures) {
            val label = fixture.name
            val yuv = toI420(fixture)
            val text = FrameStatsDump.computeDump(
                yPlane = yuv.y,
                uPlane = yuv.u,
                vPlane = yuv.v,
                yRowStride = yuv.yRowStride,
                uRowStride = yuv.uRowStride,
                width = yuv.width,
                height = yuv.height,
                label = label
            )

            // 三条自检：格式与 iOS 侧同源，落盘口径不能在这里破功。
            assertTrue(
                "$label 的首行不是 ### 标题：${text.lineSequence().firstOrNull()}",
                text.startsWith("### $label\n")
            )
            assertFalse("$label 的文本里出现了 CR —— 换行必须只有 LF", text.contains('\r'))

            val outFile = File(outputDir, "$label.android.dump.txt")
            // 显式 UTF-8，不依赖平台默认编码，也不写 BOM。
            outFile.writeBytes(text.toByteArray(StandardCharsets.UTF_8))

            val head = text.lineSequence().take(2).joinToString(" / ")
            println("  wrote ${outFile.name}  ($head)")
        }

        val written = outputDir.listFiles { f -> f.name.endsWith(".android.dump.txt") }?.size ?: 0
        assertEquals("落盘文件数应等于图集张数", fixtures.size, written)

        // 反向自检：UTF-8 无 BOM。BOM 会让 iOS 侧那份文件的首行多出三个不可见字节，
        // diff 报「第一行就不同」，而肉眼完全看不出来。
        val first = File(outputDir, "${fixtures.first().name}.android.dump.txt")
        val bytes = first.readBytes()
        assertFalse(
            "落盘文件带了 UTF-8 BOM —— iOS 侧没有，diff 会在首行假报差异",
            bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        )
        println("OK: ${fixtures.size} 张图 -> ${outputDir.absolutePath}")
    }

    /**
     * 解一张 PNG 成 I420 三平面。
     *
     * 解码走 [PngDecoder]（仓库自己的无损解码器），RGB→I420 走 [RgbToYuv]（复用）。
     * 两步都不碰 `android.graphics.Bitmap` —— 那是桩实现，纯 JVM 单测里拿不到像素。
     *
     * @param fixture 图集里的一个 PNG
     * @return 紧凑 I420 平面（yRowStride = width、uRowStride = chromaWidth）
     */
    private fun toI420(fixture: File): YuvImage {
        val decoded = PngDecoder.decode(fixture.readBytes())
        assertTrue("$fixture 尺寸过小：${decoded.width}x${decoded.height}", decoded.width >= 32 && decoded.height >= 32)
        return RgbToYuv.toI420(decoded)
    }

    /**
     * 定位 `docs/fixtures/frame-stats`。
     *
     * 单元测试的工作目录不保证是仓库根（AGP 默认是模块目录 `app/`），
     * 所以从当前目录往上找四级，取第一个存在的。
     * 找不到就报出**当前工作目录**，不然这条错误信息等于没说。
     */
    private fun locateFixturesDir(): File {
        val relative = File(File(File("docs"), "fixtures"), "frame-stats")
        var base: File? = File("").absoluteFile
        repeat(4) {
            val dir = base ?: return@repeat
            val candidate = File(dir, relative.path)
            if (candidate.isDirectory) return candidate.canonicalFile
            base = dir.parentFile
        }
        error(
            "找不到图集目录 docs/fixtures/frame-stats（当前工作目录：${File("").absolutePath}）" +
                " —— 请从仓库根或 app/ 目录运行；图集由 tools/fixtures/gen_frame_stats_fixtures.py 生成"
        )
    }
}
