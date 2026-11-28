package com.shottuner.app.knowledge

import com.shottuner.app.model.SceneType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * iOS 兜底提示（[TechniqueTipFallback]）的测试。
 *
 * ## 这份测试在守什么
 *
 * 1.3.4 把 Android 的 R10 兜底池改成「从技法库取 + 按 48 秒窗口轮换」之后，15 条新技巧
 * 在 Android 上真的会被推出来；但 iOS 的提示只取 `ScoreBoard` 的分数建议，于是同一批
 * 技巧在 iOS 上只进了图鉴、取景时一句都不出现。[TechniqueTipFallback] 补的就是这一段，
 * 而这四件事都是"错了不会报错、只会表现成'提示有点怪'"的类型，必须逐条钉住：
 *
 * - **相位**：同一窗口内不许换句（换了就是文字在抖），跨窗口必须换（不换就是"又看不见了"）；
 * - **不出精选话术**：那几句是 Android 的保底句，iOS 已经有分数建议，不该退化成它们；
 * - **不出空卡片**：每个能选出来的 id 都得解析出文案；
 * - **空池返回 null**：没有候选就说没有，不许编一条、也不许抛异常。
 */
class TechniqueTipFallbackTest {

    /** 一个轮换窗口的长度（毫秒）。与 Android 的 R10 共用同一个常量，不另立数。 */
    private val window = PromptEngine.FALLBACK_ROTATE_MS

    /**
     * 精选话术的 id —— **故意写字面量**，不跟着 `PromptEngine` 的 private 常量走。
     *
     * 理由与 `PromptEngineTest.fallbackPoolIsBounded` 一致：常量被改名 / 被换内容而行为
     * 没变时，这里应当红，逼人回来看一眼"iOS 的兜底是不是也一起变了"。
     */
    private val curatedIds: Set<String> = setOf(
        "light_white_add_black_minus",
        "basic_protect_highlights",
        "night_handheld_static",
        "portrait_backlit_face_first",
        "portrait_eyes_thirds",
        "portrait_headroom",
        "landscape_horizon_level"
    )

    /** 第 [index] 个窗口的起点时刻。 */
    private fun windowStart(index: Long): Long = index * window

    /**
     * iOS 兜底在 [nowMs] 这一刻的可选池 —— 就是 `PromptEngine` 的兜底池剔掉精选话术。
     *
     * ★ 这里的"剔掉"是**独立算一遍**（按上面那组字面量），不复用实现里的
     *   `isCuratedFallbackText`：若实现里的过滤条件写错（例如漏剔、或剔错了一批），
     *   测试照抄实现就永远发现不了。只有 id → 精选集合这条对应关系单独用一条断言对齐
     *   （见 [curatedIdsMatchTheEngine]）。
     */
    private fun pickablePoolAt(scene: SceneType, nowMs: Long): List<String> =
        PromptEngine.fallbackPool(scene, nowMs).filterNot { it in curatedIds }

    /** 一分钟 30 帧的取景节奏下，一个窗口内的采样点（含首尾）。 */
    private fun offsetsInWindow(): List<Long> = buildList {
        add(0L)
        var t = 0L
        while (t < window) {
            add(t)
            t += window / 100L
        }
        add(window - 1L)
    }

    /**
     * ① 同一窗口内连续 100 次调用必须是同一条。
     *
     * 取景浮层每秒重算一次；若按 `nowMs` 本身取下标，同一秒内就会换句，用户看到的是
     * 文字每秒抖一下 —— 那比没有提示更糟。相位只能跟窗口走。
     */
    @Test
    fun pickIsStableWithinOneWindow() {
        var checked = 0
        for (scene in SceneType.values()) {
            for (windowIndex in 0L..40L) {
                val base = windowStart(windowIndex)
                val first = TechniqueTipFallback.pick(scene, base)
                for (offset in offsetsInWindow()) {
                    assertEquals(
                        first,
                        TechniqueTipFallback.pick(scene, base + offset),
                        "$scene 第 $windowIndex 个窗口内偏移 ${offset}ms 时换了句 —— 同一窗口内必须恒定"
                    )
                    checked++
                }
            }
        }
        assertTrue(checked > 500, "只检查了 $checked 次采样，覆盖不足，断言形同虚设")
    }

    /**
     * ② 相邻两个窗口必须换一条；库内候选撑破一个窗口的场景，还得真的轮出第一批窗口之外。
     *
     * 两个断言各守一半：「相邻不同」守"在轮换"，「轮出第一批窗口」守"轮得到新条目"。
     * 只断言前者的话，一条 3 条的池子（ACTION）也会通过，但那代表不了"15 条新技巧可达"。
     */
    @Test
    fun pickRotatesAcrossWindows() {
        // 池子里至少两条才有"轮换"可言；一条的场景（未来某天可能出现）跳过并计数，
        // 免得这条悄悄退化成空转。
        var rotatingScenes = 0
        for (scene in SceneType.values()) {
            if (pickablePoolAt(scene, 0L).size < 2) continue
            rotatingScenes++
            for (n in 0L until 40L) {
                assertNotEquals(
                    TechniqueTipFallback.pick(scene, windowStart(n)),
                    TechniqueTipFallback.pick(scene, windowStart(n + 1)),
                    "$scene 第 $n / ${n + 1} 个窗口给了同一条 —— 轮换没在走"
                )
            }
        }
        assertTrue(rotatingScenes >= 5, "只有 $rotatingScenes 个场景参与轮换断言，覆盖不足")

        // 库内候选超过一个窗口（6 条）的场景：跑 60 个窗口后必须轮出第一批窗口之外的条目。
        // 名单来自 `PromptEngineTest.fallbackPoolRotatesAcrossCycles` 的同一组判断
        // （ACTION 3 条 / GENERAL 6 条装得下一个窗口，池子本身不滑动）。
        val wideScenes = listOf(
            SceneType.NIGHT, SceneType.BACKLIT, SceneType.PORTRAIT,
            SceneType.LANDSCAPE, SceneType.INDOOR
        )
        for (scene in wideScenes) {
            val firstWindow = pickablePoolAt(scene, 0L).toSet()
            val seen = (0L until 60L)
                .mapNotNull { TechniqueTipFallback.pick(scene, windowStart(it)) }
                .toSet()
            val outside = seen - firstWindow
            assertTrue(
                outside.isNotEmpty(),
                "$scene 60 个窗口里一次都没轮到第一批窗口之外的技法，seen=$seen first=$firstWindow"
            )
        }
    }

    /**
     * ③ 精选话术永不被 iOS 兜底选中。
     *
     * 那几句（`白加黑减` / `先保高光` …）是 Android R10 在"连库内候选都没轮到"时的保底
     * 话术。iOS 的 `ScoreBoard` 已经在给分数相关的建议，兜底再退化成同样几句泛泛口诀，
     * 等于把这次补丁的意义抹掉。
     *
     * 顺带断言测试里的字面量集合与引擎的精选表**完全一致**（双向）：哪边改了而另一边
     * 没跟上，这里立刻红 —— 否则"剔掉精选"可能悄悄变成"剔掉了一批凑巧不在表里的 id"。
     */
    @Test
    fun pickNeverReturnsCuratedIds() {
        var picked = 0
        for (scene in SceneType.values()) {
            for (n in 0L until 60L) {
                val id = TechniqueTipFallback.pick(scene, windowStart(n)) ?: continue
                picked++
                assertTrue(
                    id !in curatedIds,
                    "$scene 第 $n 个窗口的 iOS 兜底选中了精选话术 $id —— 那是 Android 的保底句，不该在这里出现"
                )
            }
        }
        assertTrue(picked > 100, "只取到 $picked 条，覆盖不足")

        // 字面量集合 == 引擎的精选表（双向）
        for (scene in SceneType.values()) {
            for (n in 0L until 20L) {
                for (id in PromptEngine.fallbackPool(scene, windowStart(n))) {
                    assertEquals(
                        id in curatedIds,
                        PromptEngine.isCuratedFallbackText(id),
                        "$scene 第 $n 个窗口的池子条目 $id 在测试字面量集合与引擎精选表里判定不一致"
                    )
                }
            }
        }
    }

    /**
     * ④ 池子里没有可选条目时返回 null，而不是抛异常 / 编一条出来。
     *
     * 「没有候选」在 iOS 侧的正确表现是 `tipText` 留空、浮层不画提示卡 ——
     * 与 Android 的 R10「解析不出文案就这一拍不出牌」同一个口径。
     *
     * 断言做成**双向**（`pick == null` ⟺ 该窗口的可选池为空），并额外扫三个极端时刻：
     * 负数 / `Long.MIN_VALUE` 是 `mod` 的经典越界来源（写成 `%` 就会拿到负下标）。
     */
    @Test
    fun pickReturnsNullForEmptyPool() {
        val extremeTimes = listOf(
            Long.MIN_VALUE,
            -1L,
            -PromptEngine.FALLBACK_ROTATE_MS,
            0L,
            windowStart(7L) + window / 2L,
            Long.MAX_VALUE
        )

        var checked = 0
        for (scene in SceneType.values()) {
            for (now in extremeTimes) {
                val pool = pickablePoolAt(scene, now)
                val picked = TechniqueTipFallback.pick(scene, now)
                if (pool.isEmpty()) {
                    assertEquals(null, picked, "$scene 在 now=$now 没有可选条目，应当返回 null")
                } else {
                    assertNotNull(picked, "$scene 在 now=$now 有 ${pool.size} 条可选，却返回了 null")
                    assertTrue(
                        picked in pool,
                        "$scene 在 now=$now 选出的 $picked 不在这一窗的池子里：$pool"
                    )
                }
                checked++
            }
        }
        assertTrue(checked >= 40, "只检查了 $checked 组，覆盖不足")
    }

    /**
     * ⑤ 每一个可能被选出来的 id 都要解析出文案。
     *
     * 解析失败的表现是 `tipText` 为空、提示卡整块不画 —— 用户那次窗口就白等了 48 秒，
     * 而日志里什么都没有。所以这里不只遍历 `pick` 的实际结果，连**整窗池子**一起查：
     * 只要某个窗口里存在一条解析不出文案的候选，这条就红，不必等它真的被选中。
     */
    @Test
    fun textResolvesForEveryPickable() {
        var resolved = 0
        val broken = ArrayList<String>()
        for (scene in SceneType.values()) {
            for (n in 0L until 41L) {
                val now = windowStart(n)
                for (id in pickablePoolAt(scene, now)) {
                    if (TechniqueTipFallback.text(id).isEmpty()) broken.add("$scene/窗口$n/$id")
                    resolved++
                }
                val picked = TechniqueTipFallback.pick(scene, now)
                if (picked != null) {
                    assertTrue(
                        TechniqueTipFallback.text(picked).isNotEmpty(),
                        "$scene 第 $n 个窗口选出了 $picked，但解析不出文案（会推出空卡片）"
                    )
                }
            }
        }
        assertTrue(resolved > 200, "只解析了 $resolved 条，覆盖不足")
        assertTrue(broken.isEmpty(), "这些候选解析不出文案：$broken")
    }
}
