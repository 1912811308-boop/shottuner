package com.shottuner.app.knowledge

import com.shottuner.app.model.SceneType
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * 44 条新技法的数据体检。
 *
 * 这个文件只回答一个问题：**阶段二的转写有没有走样**。
 * 所以断言分三类 —— 条数与分布、字段字数口径、以及「哪些条目不许进推送池」。
 */
class TechniqueBookV15Test {

    private val expectedCounts = mapOf(
        TechCategory.PORTRAIT to 10,
        TechCategory.CAMERA_MOVE to 8,
        TechCategory.SELFIE to 5,
        TechCategory.PITFALL to 4,
        TechCategory.LANDSCAPE to 4,
        TechCategory.PARAMS to 3,
        TechCategory.NIGHT to 3,
        TechCategory.LIGHT to 3,
        TechCategory.STREET to 2,
        TechCategory.FOOD to 2
    )

    // ── 条数与分布 ─────────────────────────────────────────────────────────────

    @Test
    fun hasExactlyFortyFourEntries() {
        assertEquals(44, TechniqueBookV15.list.size, "v1.5 新增必须是 44 条")
        assertEquals(TechniqueBookV15.EXPECTED_COUNT, TechniqueBookV15.list.size)
        assertTrue(TechniqueBookV15.isReady())
    }

    @Test
    fun categoryDistributionMatchesProductSpec() {
        val actual = TechniqueBookV15.categoryCounts()
        assertEquals(expectedCounts.keys, actual.keys, "分类集合必须与产品口径一致")
        for ((category, expected) in expectedCounts) {
            assertEquals(expected, actual[category], "${category.label} 条数不对")
        }
        assertEquals(44, actual.values.sum(), "分布合计必须等于 44")
    }

    @Test
    fun idsAreUniqueAndDisjointFromLegacyBook() {
        val ids = TechniqueBookV15.list.map { it.id }
        assertEquals(ids.size, ids.distinct().size, "新库内 id 必须唯一")

        // 用 legacy（1.4 及以前那 39 条）比对，不能用 all —— all 从 1.5 起已经包含这 44 条
        val legacy = PhotographyBook.legacy.map { it.id }.toSet()
        assertEquals(39, legacy.size, "老库应该是 39 条")
        val overlap = ids.filter { it in legacy }
        assertTrue(overlap.isEmpty(), "新 44 条不能与老 39 条撞 id，撞了这些：$overlap")

        assertEquals(83, PhotographyBook.all.size, "老库 + 新库 = 83")
        assertEquals(
            44,
            PhotographyBook.all.count { it.id in ids },
            "PhotographyBook.all 必须包含这 44 条"
        )
        assertEquals(
            PhotographyBook.all.size,
            PhotographyBook.all.map { it.id }.distinct().size,
            "合并库 id 必须全局唯一"
        )
        assertEquals(
            PhotographyBook.all.size,
            TechniqueBookV15.byId.size,
            "byId 必须覆盖全库"
        )
    }

    // ── 文案字数口径（与 docs/TECHNIQUE-v15.md 的闸门一致） ────────────────────

    @Test
    fun textFieldsMeetTheSameGateAsLegacyBook() {
        val problems = ArrayList<String>()
        for (tech in TechniqueBookV15.list) {
            val id = tech.id
            fun check(ok: Boolean, message: String) {
                if (!ok) problems.add("$id $message")
            }
            check(tech.title.length in 8..16, "title 长度 ${tech.title.length}")
            check(tech.oneLiner.length in 4..20, "oneLiner 长度 ${tech.oneLiner.length}")
            check(tech.whenTo.length in 40..80, "whenTo 长度 ${tech.whenTo.length}")
            check(tech.why.length in 60..120, "why 长度 ${tech.why.length}")
            check(tech.steps.size in 3..6, "steps 数量 ${tech.steps.size}")
            check(tech.check.length in 20..70, "check 长度 ${tech.check.length}")
            check(tech.mistakes.isNotEmpty(), "mistakes 不能为空")
            check(tech.params.isNotEmpty(), "params 不能为空")
            // 字数标注是校验用的，不该漏进 UI
            check(!tech.title.contains("字）"), "title 里残留了字数标注")
            check(!tech.why.contains("字）"), "why 里残留了字数标注")
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun noNewEntryRequiresTeleWithoutBeingUniversal() {
        for (tech in TechniqueBookV15.list) {
            assertFalse(
                tech.trigger.scenes.isEmpty() && tech.trigger.requiresTele,
                "${tech.id} 不能既是通用条目又要求长焦"
            )
        }
    }

    // ── 推送池纪律 ─────────────────────────────────────────────────────────────

    @Test
    fun browseOnlyEntriesStayOutOfEveryPushPool() {
        val browseOnly = TechniqueBookV15.BROWSE_ONLY_IDS
        assertEquals(3, browseOnly.size, "仅浏览的三条必须齐全")
        for (id in browseOnly) {
            assertTrue(
                id in TechniqueBookV15.byId,
                "$id 必须在技法库里（否则它不是「仅浏览」而是「不存在」）"
            )
            assertFalse(
                id in VideoMovePlanner.allMoveIds(),
                "$id 不能出现在运镜池里"
            )
            assertFalse(
                TechniqueMatcher.poolFor(PromptEngine.TAB_PHOTO).any { it.id == id },
                "$id 不能出现在拍照页推荐池里"
            )
            assertFalse(
                TechniqueMatcher.poolFor(PromptEngine.TAB_VIDEO).any { it.id == id },
                "$id 不能出现在录像页推荐池里"
            )
        }
    }

    @Test
    fun selfieEntriesStayOutOfEveryPushPool() {
        assertEquals(5, TechniqueBookV15.SELFIE_IDS.size)
        for (id in TechniqueBookV15.SELFIE_IDS) {
            val tech = TechniqueBookV15.byId[id]
            assertTrue(tech != null, "$id 必须存在于技法库")
            assertEquals(TechCategory.SELFIE, tech!!.category, "$id 分类必须是 SELFIE")
            assertFalse(id in VideoMovePlanner.allMoveIds(), "$id 不能出现在运镜池")
            assertFalse(
                TechniqueMatcher.poolFor(PromptEngine.TAB_PHOTO).any { it.id == id } ||
                    TechniqueMatcher.poolFor(PromptEngine.TAB_VIDEO).any { it.id == id },
                "$id 不能出现在推荐池"
            )
        }
    }

    /**
     * 结构断言：引擎声明的「可发射 id 面」与禁推集合（自拍 5 + 仅浏览 3）必须互斥。
     *
     * 这条替换掉了原来那个穷举 10 080 组信号的用例 —— 那个用例**不可能失败**：
     * 它拿"实际发射出来的 id"去比禁推集合，而可发射面本来就是按构造与禁推集合分开的，
     * 网格再密也只是在证明"这两组常量没有交集"（QA P2-1 判定的结构性假阳性）。
     *
     * 光有这一条仍然不够：`allEmittableTipIds()` 是手工维护的清单，
     * 若有人新增规则却忘了登记，这条断言照样绿。所以下面还有两条配套断言：
     * - [declaredEmittableIdsAreReallyInTheBook]：清单里每一条都必须真实存在且可推送；
     * - [emittedIdsStayWithinTheDeclaredSet]：网格跑出来的每个 id 都必须在清单内 ——
     *   规则若偷偷吐了清单外的东西，这一条会红。
     */
    @Test
    fun emittableTipIdsAreDisjointFromBannedIds() {
        val banned = TechniqueBookV15.SELFIE_IDS + TechniqueBookV15.BROWSE_ONLY_IDS
        val leaked = PromptEngine.allEmittableTipIds() intersect banned
        assertTrue(
            leaked.isEmpty(),
            "引擎可发射面里出现了禁推条目：$leaked（自拍与仅浏览条目不得进入任何推送池）"
        )
        // 清单不能是空的，否则上面那条断言恒真
        assertTrue(PromptEngine.allEmittableTipIds().size >= 11, "可发射清单为空，说明断言失效")
    }

    @Test
    fun declaredEmittableIdsAreReallyInTheBook() {
        for (id in PromptEngine.allEmittableTipIds()) {
            val tech = TechniqueBookV15.byId[id]
            assertTrue(tech != null, "PromptEngine 声明可发射「$id」，但技法库里没有这条")
            assertTrue(
                id in TechniqueBookV15.pushEligibleIds,
                "「$id」不在可推送白名单里，生产环境会被静默过滤，等于规则白写"
            )
        }
    }

    @Test
    fun pushEligibleIdsIsTheBookMinusBanned() {
        val banned = TechniqueBookV15.SELFIE_IDS + TechniqueBookV15.BROWSE_ONLY_IDS
        assertEquals(
            TechniqueBookV15.byId.keys - banned,
            TechniqueBookV15.pushEligibleIds,
            "可推送白名单 = 全库 − 自拍 − 仅浏览"
        )
        assertEquals(
            PhotographyBook.all.size - banned.size,
            TechniqueBookV15.pushEligibleIds.size,
            "白名单条数 = 83 − 8"
        )
        assertTrue((TechniqueBookV15.pushEligibleIds intersect banned).isEmpty(), "禁推条目不得出现在白名单里")
    }

    /**
     * 网格扫描：跑出来的**每一个** id 都必须在 `allEmittableTipIds()` 清单内。
     *
     * 这条才是让"清单漏登记"变成红色的那道闸。网格规模刻意控制在万级：
     * 再多也撞不出新东西，只是拖慢测试。
     */
    @Test
    fun emittedIdsStayWithinTheDeclaredSet() {
        val declared = PromptEngine.allEmittableTipIds()
        val undeclared = LinkedHashSet<String>()
        var emitted = 0
        for (tab in listOf(PromptEngine.TAB_PHOTO, PromptEngine.TAB_VIDEO)) {
            for (scene in SceneType.values()) {
                for (luma in listOf(0.05f, 0.14f, 0.20f, 0.50f, 0.75f)) {
                    for (highlight in listOf(0f, 0.13f, 0.40f)) {
                        for (shadow in listOf(0f, 0.35f)) {
                            for (roll in listOf(null, 0f, 6f)) {
                                // gridOn 两种 + 「刚关」与「关很久」都要跑到，
                                // 否则 R11 的第二道门（gridOffAgoMs）根本没被覆盖
                                for (gridOffAgo in listOf(0L, 90_000L)) {
                                    for (gridOn in listOf(true, false)) {
                                        for (intent in listOf(-1, VideoIntentIndex.TALK)) {
                                            for (idle in listOf(-1L, 20_000L)) {
                                                for (pacedAt in listOf(-1L, 20_000L)) {
                                                    val signals = PromptSignals(
                                                        tech = TechSignals(
                                                            meanLuma = luma,
                                                            highlightClip = highlight,
                                                            shadowClip = shadow,
                                                            saturation = 0.4f,
                                                            skinRatio = 0.10f,
                                                            edgeDensity = 0.2f,
                                                            centerBias = -0.10f,
                                                            hasTele = true,
                                                            hasStats = true
                                                        ),
                                                        roll = roll,
                                                        pitch = -14f,
                                                        tab = tab,
                                                        videoIntentOrdinal = intent,
                                                        scene = scene,
                                                        hasTele = true,
                                                        equivFocalMm = 70,
                                                        gridOn = gridOn,
                                                        stability = 0.05f,
                                                        gridOffAgoMs = gridOffAgo
                                                    )
                                                    val result = PromptEngine.evaluate(
                                                        signals = signals,
                                                        now = 30_000L,
                                                        lastGuideAt = pacedAt,
                                                        lastCandidateAt = idle
                                                    )
                                                    for (candidate in result) {
                                                        emitted++
                                                        if (candidate.tipId !in declared) {
                                                            undeclared.add("${candidate.ruleId}:${candidate.tipId}")
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue(emitted > 0, "网格一个候选都没产出来，说明用例本身失效了")
        assertTrue(
            undeclared.isEmpty(),
            "引擎发射了 allEmittableTipIds() 里没登记的 id：$undeclared（新增规则请同步登记清单）"
        )
    }

    @Test
    fun everyEmittedTipIdExistsInTheBook() {
        // 覆盖 11 条规则各自最典型的触发条件（含 R09 运镜与 R11 网格），
        // 断言它们点名的 tipId 都能在技法库里查到 —— 打错字不会被静默吞掉。
        val samples = listOf(
            signals(roll = 6f),
            signals(tech = tech(highlightClip = 0.3f)),
            signals(tech = tech(meanLuma = 0.10f)),
            signals(tech = tech(meanLuma = 0.10f), scene = SceneType.NIGHT),
            signals(tech = tech(meanLuma = 0.55f, skinRatio = 0.15f, centerBias = -0.15f)),
            signals(tech = tech(meanLuma = 0.5f, skinRatio = 0.12f), scene = SceneType.PORTRAIT, equivFocalMm = 24),
            signals(tech = tech(meanLuma = 0.12f, skinRatio = 0.10f), scene = SceneType.NIGHT),
            signals(tech = tech(meanLuma = 0.5f, saturation = 0.5f, edgeDensity = 0.3f)),
            signals(tech = tech(meanLuma = 0.15f), equivFocalMm = 70),
            signals(tab = PromptEngine.TAB_VIDEO, videoIntentOrdinal = VideoIntentIndex.TALK),
            signals(tech = tech(meanLuma = 0.5f), stability = 0.05f),
            signals(gridOn = false)
        )

        val missing = LinkedHashSet<String>()
        for (sample in samples) {
            for (candidate in PromptEngine.evaluate(sample, 30_000L, lastCandidateAt = 1_000L)) {
                if (candidate.tipId !in TechniqueBookV15.byId) missing.add(candidate.tipId)
            }
        }
        assertTrue(missing.isEmpty(), "这些 tipId 在技法库里不存在：$missing")
    }

    @Test
    fun movePoolsOnlyReferenceRealCameraMoveEntries() {
        val pool = VideoMovePlanner.allMoveIds()
        assertTrue(pool.isNotEmpty(), "运镜池不能为空")
        for (id in pool) {
            val tech = TechniqueBookV15.byId[id]
            assertTrue(tech != null, "运镜池引用了不存在的 id：$id")
            assertEquals(TechCategory.CAMERA_MOVE, tech!!.category, "$id 分类必须是 CAMERA_MOVE")
        }
        assertEquals(8, VideoMovePlanner.intentCount, "8 个录像意图各一个池")
        for (ordinal in 0 until VideoMovePlanner.intentCount) {
            assertTrue(VideoMovePlanner.poolFor(ordinal).isNotEmpty(), "意图 $ordinal 的运镜池不能为空")
        }
    }

    // ── 小工具 ─────────────────────────────────────────────────────────────────

    private object VideoIntentIndex {
        /** `VideoIntent.TALK` 的序号：WALK=0, TALK=1（顺序见 `video/VideoRecommender.kt`）。 */
        const val TALK = 1
    }

    private fun tech(
        meanLuma: Float = 0.5f,
        highlightClip: Float = 0f,
        shadowClip: Float = 0f,
        saturation: Float = 0.3f,
        skinRatio: Float = 0f,
        edgeDensity: Float = 0.2f,
        centerBias: Float = 0f
    ): TechSignals = TechSignals(
        meanLuma = meanLuma,
        highlightClip = highlightClip,
        shadowClip = shadowClip,
        saturation = saturation,
        skinRatio = skinRatio,
        edgeDensity = edgeDensity,
        centerBias = centerBias,
        hasTele = true,
        hasStats = true
    )

    private fun signals(
        tech: TechSignals = tech(),
        roll: Float? = 0f,
        tab: Int = PromptEngine.TAB_PHOTO,
        scene: SceneType = SceneType.GENERAL,
        equivFocalMm: Int = 70,
        gridOn: Boolean = true,
        videoIntentOrdinal: Int = -1,
        stability: Float = 0f
    ): PromptSignals = PromptSignals(
        tech = tech,
        roll = roll,
        pitch = -14f,
        tab = tab,
        videoIntentOrdinal = videoIntentOrdinal,
        scene = scene,
        hasTele = true,
        equivFocalMm = equivFocalMm,
        gridOn = gridOn,
        stability = stability
    )
}
