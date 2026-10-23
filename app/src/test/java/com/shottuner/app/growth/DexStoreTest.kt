package com.shottuner.app.growth

import com.shottuner.app.analysis.ScoreBoard
import com.shottuner.app.analysis.ScoreDimension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图鉴数据层测试（G3）。
 *
 * ## 这个文件测不到的那一半
 * 文件名叫 `DexStoreTest`，但 [DexStore] 是 Android 侧：它要 `Context`
 * 和 `SharedPreferences`，local unit test 里拿不到（项目没有 Robolectric，
 * 也不允许新增第三方依赖）。所以**这里测的是 [TechniqueDex] + `shouldUnlock`
 * 这一整层纯 JVM 逻辑**，也就是 `DexStore` 的全部决策依据。
 * 真正落盘的三件事只能靠真机手验：
 *   1. `unlock()` 的"复制 → 加 → 写"能不能真的写出一个 StringSet；
 *   2. `clear()` 之后 `unlockedCount` 是否归 0；
 *   3. 跨天之后 `lastUnlockDate` 的比较是否放行。
 * 把它们单独列出来，是提醒接手的人：CI 全绿不等于这三件事成立。
 *
 * ## 每条用例对应一条"绝不能错"的口径
 *   - 83 条一个都不能少、一个都不能重；
 *   - 没测到的维度按"未测"处理，不能当 0 分放行；
 *   - 全满分那句 tipId 为空的话，不解锁任何条目；
 *   - 一天最多一条。
 */
class DexStoreTest {

    // 与 ScoreBoard.MAX_* 一致。这里刻意写字面量而不是引常量：
    // 单测要能独立发现"评分口径改了而图鉴没跟上"，引常量就等于把两件事绑死了
    private val MAX_LEVEL = 25
    private val MAX_EXPOSURE = 30
    private val MAX_COMPOSITION = 25
    private val MAX_CLARITY = 20

    private val DAY_1 = "20250601"
    private val DAY_2 = "20250602"

    // ── 1. 条数 ───────────────────────────────────────────────────────────────

    @Test
    fun all_containsEveryTechniqueInTheBook() {
        assertEquals("图鉴条数必须与知识库一致", 83, TechniqueDex.total())
        assertEquals(83, TechniqueDex.all().size)
        assertEquals("total() 与 all() 必须同源", TechniqueDex.all().size, TechniqueDex.total())
    }

    @Test
    fun sources_partitionTheBook() {
        val v15 = TechniqueDex.bySource(TechniqueDex.SOURCE_V15)
        val book = TechniqueDex.bySource(TechniqueDex.SOURCE_BOOK)

        assertEquals("v15 那 44 条", 44, v15.size)
        assertEquals("老库那 39 条", 39, book.size)
        assertEquals("两个来源加起来必须是全部 83 条", 83, v15.size + book.size)
    }

    // ── 2. 排重 ───────────────────────────────────────────────────────────────

    @Test
    fun idsAreUniqueAcrossSources() {
        val ids = TechniqueDex.ids()
        assertEquals(
            "id 必须唯一，重复的条数：${ids.size - ids.toSet().size}",
            ids.toSet().size,
            ids.size
        )

        // 两个来源的 id 不能有交集：有交集说明同一条技法被登记了两次，
        // 83 这个分母会虚高，而 byId 里谁覆盖谁纯属运气
        val v15Ids = TechniqueDex.bySource(TechniqueDex.SOURCE_V15).map { it.id }.toSet()
        val bookIds = TechniqueDex.bySource(TechniqueDex.SOURCE_BOOK).map { it.id }.toSet()
        assertEquals(
            "v15 与 book 的 id 不能有交集：${v15Ids.intersect(bookIds)}",
            0,
            v15Ids.intersect(bookIds).size
        )
    }

    @Test
    fun everyTechniqueHasADimensionEntry() {
        // 新加技法却忘了在 DIMENSION_OF 里登记 → 它变成 null → 永远解锁不了。
        // 这种"静默少一条"最难查，所以用断言钉住
        assertEquals(
            "这些技法没有登记归属维度：${TechniqueDex.unmappedIds()}",
            emptyList<String>(),
            TechniqueDex.unmappedIds()
        )
    }

    // ── 3. byId ───────────────────────────────────────────────────────────────

    @Test
    fun byId_findsAnExistingTechnique() {
        val entry = TechniqueDex.byId("landscape_horizon_level")

        assertNotNull(entry)
        assertEquals("landscape_horizon_level", entry!!.id)
        assertEquals("地平线一定端平，歪一点就废", entry.title)
        assertEquals(TechniqueDex.SOURCE_V15, entry.source)
    }

    @Test
    fun byId_returnsNullForUnknownId() {
        assertNull(TechniqueDex.byId("no_such_technique"))
        assertNull("空串不是一条技法", TechniqueDex.byId(""))
        assertNull(TechniqueDex.byId("landscape_horizon_third"))
    }

    @Test
    fun everyEntry_hasReadableContent() {
        for (entry in TechniqueDex.all()) {
            assertTrue("${entry.id} 的标题不能为空", entry.title.isNotBlank())
            assertTrue("${entry.id} 的一句话解释不能为空", entry.body.isNotBlank())
            assertTrue("${entry.id} 的 id 不能为空", entry.id.isNotBlank())
        }
    }

    // ── 4. byDimension ────────────────────────────────────────────────────────

    @Test
    fun byDimension_returnsOnlyEntriesOfThatDimension() {
        for (dimension in ScoreDimension.values()) {
            val group = TechniqueDex.byDimension(dimension)
            assertTrue("${dimension}组不该是空的", group.isNotEmpty())
            for (entry in group) {
                assertEquals("${entry.id} 不该出现在 ${dimension} 组里", dimension, entry.dimension)
            }
        }
    }

    @Test
    fun byDimension_plusNullEntries_accountsForAllEightyThree() {
        var grouped = 0
        for (dimension in ScoreDimension.values()) {
            grouped += TechniqueDex.byDimension(dimension).size
        }
        val knowledgeOnly = TechniqueDex.all().count { it.dimension == null }

        assertEquals(
            "四维条目 + 纯知识条目 必须等于 83（四维 $grouped + 纯知识 $knowledgeOnly）",
            83,
            grouped + knowledgeOnly
        )
    }

    @Test
    fun byDimension_matchesTheSevenMissionTips() {
        // 这 7 条是当前 MissionEngine 唯一能派发出去的 tipId，
        // 也是当前唯一可能解锁的条目。它们归哪一维，直接决定解锁判定用哪个满分
        assertEntry("landscape_horizon_level", ScoreDimension.LEVEL)
        assertEntry("basic_protect_highlights", ScoreDimension.EXPOSURE)
        assertEntry("light_white_add_black_minus", ScoreDimension.EXPOSURE)
        assertEntry("portrait_backlit_face_first", ScoreDimension.EXPOSURE)
        assertEntry("portrait_eyes_thirds", ScoreDimension.COMPOSITION)
        assertEntry("portrait_headroom", ScoreDimension.COMPOSITION)
        assertEntry("move_elbow_tucked", ScoreDimension.CLARITY)
    }

    @Test
    fun byDimension_keepsSelfieMoveParamsPitfallOutOfEveryGroup() {
        // 自拍 / 运镜 / 参数 / 避坑这四类是纯浏览知识，
        // 唯一例外是 move_elbow_tucked（它是 ScoreBoard 的清晰度建议）
        for (entry in TechniqueDex.all()) {
            val isKnowledgeOnly = entry.id.startsWith("selfie_") ||
                entry.id.startsWith("params_") ||
                entry.id.startsWith("pitfall_") ||
                (entry.id.startsWith("move_") && entry.id != "move_elbow_tucked")
            if (isKnowledgeOnly) {
                assertNull("${entry.id} 是纯知识条目，不该归属任何维度", entry.dimension)
            }
        }
    }

    // ── 5. 顺序 ───────────────────────────────────────────────────────────────

    @Test
    fun all_isStableAcrossCalls() {
        assertEquals(TechniqueDex.all(), TechniqueDex.all())
        assertEquals(TechniqueDex.ids(), TechniqueDex.all().map { it.id })
        // 老库 39 条在前，v15 44 条在后 —— 册子的页码要稳定
        assertEquals(TechniqueDex.SOURCE_BOOK, TechniqueDex.all().first().source)
        assertEquals(TechniqueDex.SOURCE_V15, TechniqueDex.all().last().source)
    }

    // ── 6. shouldUnlock 主路径 ────────────────────────────────────────────────

    @Test
    fun shouldUnlock_happyPathIsTrue() {
        assertTrue(
            shouldUnlock(
                tipId = "landscape_horizon_level",
                dimension = ScoreDimension.LEVEL,
                dimScore = 25,
                dimMax = MAX_LEVEL,
                alreadyUnlocked = false,
                lastUnlockDate = null,
                today = DAY_1
            )
        )
    }

    @Test
    fun shouldUnlock_alreadyUnlockedIsFalse() {
        assertFalse(
            "已解锁过的条目不能再解锁一次",
            shouldUnlock(
                tipId = "landscape_horizon_level",
                dimension = ScoreDimension.LEVEL,
                dimScore = 25,
                dimMax = MAX_LEVEL,
                alreadyUnlocked = true,
                lastUnlockDate = null,
                today = DAY_1
            )
        )
    }

    @Test
    fun shouldUnlock_unmeasuredDimensionIsFalse() {
        // -1 是"该维度未测"的约定。没测到 ≠ 得 0 分 —— 0 分至少还是个判断，
        // 未测是根本没有判断，不能拿它放行
        for (score in listOf(-1, -100)) {
            assertFalse(
                "未测的维度（$score）不能解锁",
                shouldUnlock(
                    tipId = "portrait_eyes_thirds",
                    dimension = ScoreDimension.COMPOSITION,
                    dimScore = score,
                    dimMax = MAX_COMPOSITION,
                    alreadyUnlocked = false,
                    lastUnlockDate = null,
                    today = DAY_1
                )
            )
        }
    }

    @Test
    fun shouldUnlock_scoreBelowEightyPercentIsFalse() {
        // 构图满分 25，80% 线是 20
        assertFalse(
            shouldUnlock(
                tipId = "portrait_eyes_thirds",
                dimension = ScoreDimension.COMPOSITION,
                dimScore = 19,
                dimMax = MAX_COMPOSITION,
                alreadyUnlocked = false,
                lastUnlockDate = null,
                today = DAY_1
            )
        )
        // 恰好压线 → 通过
        assertTrue(
            "正好 80% 应该放行，别把边界算成不通过",
            shouldUnlock(
                tipId = "portrait_eyes_thirds",
                dimension = ScoreDimension.COMPOSITION,
                dimScore = 20,
                dimMax = MAX_COMPOSITION,
                alreadyUnlocked = false,
                lastUnlockDate = null,
                today = DAY_1
            )
        )
    }

    // ── 7. 每天一条 ───────────────────────────────────────────────────────────

    @Test
    fun shouldUnlock_secondUnlockSameDayIsFalse() {
        assertFalse(
            "同一天最多解锁一条",
            shouldUnlock(
                tipId = "portrait_headroom",
                dimension = ScoreDimension.COMPOSITION,
                dimScore = 25,
                dimMax = MAX_COMPOSITION,
                alreadyUnlocked = false,
                lastUnlockDate = DAY_1,
                today = DAY_1
            )
        )
    }

    @Test
    fun shouldUnlock_nextDayIsTrue() {
        assertTrue(
            shouldUnlock(
                tipId = "portrait_headroom",
                dimension = ScoreDimension.COMPOSITION,
                dimScore = 25,
                dimMax = MAX_COMPOSITION,
                alreadyUnlocked = false,
                lastUnlockDate = DAY_1,
                today = DAY_2
            )
        )
    }

    @Test
    fun shouldUnlock_blankTodayIsFalse() {
        // 拿不到日期时宁可不解锁，也不让"每天一条"这条规则形同虚设
        assertFalse(
            shouldUnlock(
                tipId = "landscape_horizon_level",
                dimension = ScoreDimension.LEVEL,
                dimScore = 25,
                dimMax = MAX_LEVEL,
                alreadyUnlocked = false,
                lastUnlockDate = null,
                today = ""
            )
        )
    }

    // ── 8. tipId ─────────────────────────────────────────────────────────────

    @Test
    fun shouldUnlock_blankTipIdIsFalse() {
        // ScoreBoard 全满分时会给一条 tipId 为空串的建议（"这张没什么可挑的"），
        // 那句话不对应任何技法，绝不解锁
        assertFalse(
            shouldUnlock(
                tipId = "",
                dimension = ScoreDimension.CLARITY,
                dimScore = MAX_CLARITY,
                dimMax = MAX_CLARITY,
                alreadyUnlocked = false,
                lastUnlockDate = null,
                today = DAY_1
            )
        )
        assertFalse(
            shouldUnlock(
                tipId = "   ",
                dimension = ScoreDimension.CLARITY,
                dimScore = MAX_CLARITY,
                dimMax = MAX_CLARITY,
                alreadyUnlocked = false,
                lastUnlockDate = null,
                today = DAY_1
            )
        )
    }

    @Test
    fun shouldUnlock_unknownTipIdIsFalse() {
        // 解锁一个图鉴里没有的条目，会让"已解锁 N / 83"的 N 与 83 对不上
        assertFalse(
            shouldUnlock(
                tipId = "not_a_real_technique",
                dimension = ScoreDimension.LEVEL,
                dimScore = MAX_LEVEL,
                dimMax = MAX_LEVEL,
                alreadyUnlocked = false,
                lastUnlockDate = null,
                today = DAY_1
            )
        )
    }

    @Test
    fun shouldUnlock_nullDimensionNeverUnlocks() {
        // 自拍 / 运镜 / 参数 / 避坑：没有可判定的维度，哪怕分数给足也不放行
        for (id in listOf(
            "selfie_arm_extended",
            "move_small_steps",
            "params_food_plus_third",
            "pitfall_grid_on"
        )) {
            assertFalse(
                "$id 是纯知识条目，不该解锁",
                shouldUnlock(
                    tipId = id,
                    dimension = null,
                    dimScore = 100,
                    dimMax = 100,
                    alreadyUnlocked = false,
                    lastUnlockDate = null,
                    today = DAY_1
                )
            )
        }
    }

    @Test
    fun shouldUnlock_illegalMaxIsFalse() {
        assertFalse(
            shouldUnlock(
                tipId = "landscape_horizon_level",
                dimension = ScoreDimension.LEVEL,
                dimScore = 25,
                dimMax = 0,
                alreadyUnlocked = false,
                lastUnlockDate = null,
                today = DAY_1
            )
        )
        assertFalse(
            shouldUnlock(
                tipId = "landscape_horizon_level",
                dimension = ScoreDimension.LEVEL,
                dimScore = 25,
                dimMax = -25,
                alreadyUnlocked = false,
                lastUnlockDate = null,
                today = DAY_1
            )
        )
    }

    // ── 9. 阈值口径：把达标线在四个维度上的后果钉成事实 ─────────────────

    @Test
    fun passScore_ceilToIntegerWithoutFloatingPoint() {
        assertEquals("水平满分 25 → 80% 线 20", 20, DexThresholds.passScore(ScoreDimension.LEVEL, 25))
        assertEquals("构图满分 25 → 80% 线 20", 20, DexThresholds.passScore(ScoreDimension.COMPOSITION, 25))
        assertEquals("清晰满分 20 → 80% 线 16", 16, DexThresholds.passScore(ScoreDimension.CLARITY, 20))
        assertEquals(DexThresholds.PASS_PERCENT, 80)
    }

    @Test
    fun passScore_exposureUsesAbsoluteThresholdNotPercent() {
        // 曝光是阶梯分（30/20/10/0），80%×30=24 卡在 20 与 30 的空档里永远取不到，
        // 所以曝光单独定绝对分 20，而不是跟着 80% 走
        assertEquals("曝光达标线是绝对分 20，不是 24", 20, DexThresholds.passScore(ScoreDimension.EXPOSURE, 30))
        assertEquals(DexThresholds.EXPOSURE_PASS, 20)
    }

    @Test
    fun exposureGate_isReachable() {
        // 曝光走绝对分 20：三项扣分里只扣一项就算「基本掌握」。
        // 0 / 10 分（扣两项及以上）不解锁；20 / 30 分解锁。
        for (score in listOf(0, 10)) {
            assertFalse(
                "曝光 $score/30 不该解锁（达标线 20）",
                shouldUnlock(
                    tipId = "basic_protect_highlights",
                    dimension = ScoreDimension.EXPOSURE,
                    dimScore = score,
                    dimMax = MAX_EXPOSURE,
                    alreadyUnlocked = false,
                    lastUnlockDate = null,
                    today = DAY_1
                )
            )
        }
        for (score in listOf(20, 30)) {
            assertTrue(
                "曝光 $score/30 应该解锁（达标线 20）",
                shouldUnlock(
                    tipId = "basic_protect_highlights",
                    dimension = ScoreDimension.EXPOSURE,
                    dimScore = score,
                    dimMax = MAX_EXPOSURE,
                    alreadyUnlocked = false,
                    lastUnlockDate = null,
                    today = DAY_1
                )
            )
        }
    }

    @Test
    fun levelGate_missionThresholdIsBelowTheDexLine() {
        // 任务是 2.5° 就算"到位"（MissionThresholds.LEVEL_ENTER_DEG），
        // 但那时的水平分只有 16/25 = 64%，够不到 80% 线（20）。
        // 结果是"任务过了，图鉴没亮"—— 这是任务达标线比图鉴达标线松的事实，
        // 钉在这里提醒：要么让用户继续端平到 1.8°，要么以后把两条线对齐。
        val atMissionThreshold = ScoreBoard.levelScoreFromDegrees(2.5f)
        assertEquals("2.5° 对应 16 分", 16, atMissionThreshold)
        assertFalse(
            "任务达标时的 16/25 够不到 80% 线（20）",
            shouldUnlock(
                tipId = "landscape_horizon_level",
                dimension = ScoreDimension.LEVEL,
                dimScore = atMissionThreshold,
                dimMax = MAX_LEVEL,
                alreadyUnlocked = false,
                lastUnlockDate = null,
                today = DAY_1
            )
        )
    }

    // ── 9.5 维度通道（B：扩解锁入口）────────────────────────────────────────

    @Test
    fun nextUnlockable_returnsFirstUnlockedInStableOrder() {
        // 维度通道解锁时，每次都解锁该维度下"第一条还没解锁"的，顺序与 all() 一致
        val first = TechniqueDex.nextUnlockable(ScoreDimension.LEVEL, emptySet())
        assertNotNull("LEVEL 维度应该有可解锁条目", first)
        assertEquals(ScoreDimension.LEVEL, first!!.dimension)

        // 把第一条锁掉后，返回下一条
        val second = TechniqueDex.nextUnlockable(ScoreDimension.LEVEL, setOf(first.id))
        assertNotNull(second)
        assertTrue("下一条必须与第一条不同", second!!.id != first.id)
        assertEquals(ScoreDimension.LEVEL, second.dimension)

        // 全部锁完 → null
        val allLevel = TechniqueDex.byDimension(ScoreDimension.LEVEL).map { it.id }.toSet()
        assertNull(
            "该维度全部解锁后应返回 null",
            TechniqueDex.nextUnlockable(ScoreDimension.LEVEL, allLevel)
        )
    }

    @Test
    fun nextUnlockable_nullDimensionHasNoUnlockable() {
        // 纯知识条目（dimension = null）不在任何维度池里，nextUnlockable 只收真实维度
        for (id in listOf("selfie_arm_extended", "params_food_plus_third", "pitfall_grid_on")) {
            assertNull("$id 应为 null 维度", TechniqueDex.byId(id)?.dimension)
        }
    }

    @Test
    fun dimensionGate_allFourDimensionsHaveAtLeastOneUnlockable() {
        // 维度通道依赖 byDimension 非空：四维里每一维都至少有一条能解锁，
        // 否则那个维度"拍好了也解锁不了"，通道就塌了一半
        for (dimension in ScoreDimension.values()) {
            assertTrue(
                "$dimension 维度池不能为空",
                TechniqueDex.byDimension(dimension).isNotEmpty()
            )
            assertNotNull(
                "$dimension 至少要有一条可解锁",
                TechniqueDex.nextUnlockable(dimension, emptySet())
            )
        }
    }

    // ── 10. 日期口径 ─────────────────────────────────────────────────────────

    @Test
    fun clock_formatsToEightDigitDate() {
        val today = DexClock.today()

        assertEquals(8, today.length)
        assertTrue("日期必须是纯数字（$today）", today.all { it in '0'..'9' })
        assertEquals("格式必须是 yyyyMMdd", "yyyyMMdd", DexClock.PATTERN)
        // 同一毫秒内两次格式化结果必须一致（SimpleDateFormat 的线程安全性由 ThreadLocal 兜）
        // 刻意不断言具体日期：SimpleDateFormat 用默认时区，CI 机器的时区不归我们管
        assertEquals(DexClock.format(1_700_000_000_000L), DexClock.format(1_700_000_000_000L))
    }

    // ── 辅助 ─────────────────────────────────────────────────────────────────

    private fun assertEntry(id: String, dimension: ScoreDimension) {
        val entry = TechniqueDex.byId(id)
        assertNotNull("$id 必须在图鉴里", entry)
        assertEquals("$id 应归属 $dimension", dimension, entry!!.dimension)
        assertTrue(
            "$id 必须出现在 byDimension($dimension) 里",
            TechniqueDex.byDimension(dimension).any { it.id == id }
        )
    }
}
