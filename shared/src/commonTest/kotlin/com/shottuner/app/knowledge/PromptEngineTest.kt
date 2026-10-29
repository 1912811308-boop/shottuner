package com.shottuner.app.knowledge

import com.shottuner.app.model.SceneType
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * 提示规则表测试。
 *
 * 技法库已落地 83 条（39 老 + 44 新），但这里统一传 `availableTipIds = null`（不过滤），
 * 只测"规则在什么信号下说话、说什么"。"找不到 id 就静默跳过"单独有一个用例。
 */
class PromptEngineTest {

    private fun tech(
        meanLuma: Float = 0.5f,
        highlightClip: Float = 0f,
        shadowClip: Float = 0f,
        saturation: Float = 0.3f,
        skinRatio: Float = 0f,
        edgeDensity: Float = 0.2f,
        centerBias: Float = 0f,
        hasTele: Boolean = false,
        hasStats: Boolean = true
    ): TechSignals = TechSignals(
        meanLuma = meanLuma,
        highlightClip = highlightClip,
        shadowClip = shadowClip,
        saturation = saturation,
        skinRatio = skinRatio,
        edgeDensity = edgeDensity,
        centerBias = centerBias,
        hasTele = hasTele,
        hasStats = hasStats
    )

    private fun signals(
        tech: TechSignals = tech(),
        roll: Float? = 0f,
        pitch: Float? = -14f,
        tab: Int = PromptEngine.TAB_PHOTO,
        scene: SceneType = SceneType.GENERAL,
        hasTele: Boolean = false,
        equivFocalMm: Int = 24,
        coveredTopics: Set<String> = emptySet(),
        gridOn: Boolean = true,
        gridOffAgoMs: Long = Long.MAX_VALUE,
        videoIntentOrdinal: Int = -1,
        stability: Float = 0f,
        mirrored: Boolean = false
    ): PromptSignals = PromptSignals(
        tech = tech,
        roll = roll,
        pitch = pitch,
        tab = tab,
        videoIntentOrdinal = videoIntentOrdinal,
        scene = scene,
        hasTele = hasTele,
        equivFocalMm = equivFocalMm,
        gridOn = gridOn,
        coveredTopics = coveredTopics,
        mirrored = mirrored,
        stability = stability,
        gridOffAgoMs = gridOffAgoMs
    )

    @Test
    fun allRulesHaveUniqueIds() {
        val ids = PromptEngine.ruleIds()
        assertEquals(11, ids.size, "规则表应为 11 条")
        assertEquals(ids.size, ids.distinct().size, "ruleId 必须唯一")
        assertEquals("R01_tilt", ids.first(), "第一条是倾斜规则")
        assertEquals("R11_grid_off", ids.last(), "最后一条是网格规则")
    }

    @Test
    fun tiltRuleFiresAbove3Deg() {
        assertNotNull(PromptEngine.candidateFor("R01_tilt", signals(roll = 3.1f), 0L))
        assertNull(PromptEngine.candidateFor("R01_tilt", signals(roll = 2.9f), 0L), "2.9° 不构成硬伤")
    }

    @Test
    fun tiltRuleNeedsPhotoTab() {
        assertNull(
            PromptEngine.candidateFor("R01_tilt", signals(roll = 5f, tab = PromptEngine.TAB_VIDEO), 0L),
            "录像页不报地平线"
        )
    }

    @Test
    fun tiltRuleTextCarriesDegreesAndDirection() {
        val candidate = PromptEngine.candidateFor("R01_tilt", signals(roll = 4.2f), 0L)
        assertNotNull(candidate)
        assertTrue(candidate!!.action.contains("4°"), "文案要带角度")
        assertTrue(
            candidate.action.contains("顺时针") || candidate.action.contains("逆时针"),
            "文案要带方向"
        )
        assertEquals("roll", candidate.recoverKey, "上屏后可由信号恢复自动撤下")
        assertTrue(candidate.autoResolve)
        assertEquals(800L, candidate.holdMs)
    }

    @Test
    fun overexposedRuleUsesCalibratedThreshold() {
        // §6.3 换算后的阈值是 0.12：PRD 的 0.20 在 shottuner 口径下永远报不出来
        assertNotNull(
            PromptEngine.candidateFor("R02_overexposed", signals(tech = tech(highlightClip = 0.13f)), 0L),
            "0.13 应触发"
        )
        assertNull(
            PromptEngine.candidateFor("R02_overexposed", signals(tech = tech(highlightClip = 0.11f)), 0L),
            "0.11 不触发"
        )
        assertEquals(
            ScoreBoardThresholds.CLIP_HIGH_CONVERTED,
            PromptEngine.HIGHLIGHT_CLIP_THRESHOLD,
            0.0001f,
            "换算常量必须与 ScoreBoard 对齐"
        )
    }

    @Test
    fun underexposedPicksNightVariant() {
        val night = PromptEngine.candidateFor(
            "R03_underexposed",
            signals(tech = tech(meanLuma = 0.12f), scene = SceneType.NIGHT),
            0L
        )
        assertNotNull(night)
        assertEquals("night_handheld_static", night!!.tipId)

        val day = PromptEngine.candidateFor(
            "R03_underexposed",
            signals(tech = tech(meanLuma = 0.12f), scene = SceneType.LANDSCAPE),
            0L
        )
        assertNotNull(day)
        assertEquals("light_white_add_black_minus", day!!.tipId)
    }

    @Test
    fun backlitSignalsRequired() {
        // 只有"中心比全图暗"还不够，必须同时有肤色（否则是把暗角当成人脸）
        assertNull(
            PromptEngine.candidateFor(
                "R04_backlit_face",
                signals(tech = tech(meanLuma = 0.7f, centerBias = -0.2f, skinRatio = 0f)),
                0L
            ),
            "没有肤色就不该说脸黑"
        )
        assertNotNull(
            PromptEngine.candidateFor(
                "R04_backlit_face",
                signals(tech = tech(meanLuma = 0.7f, centerBias = -0.2f, skinRatio = 0.1f)),
                0L
            ),
            "有肤色才报逆光"
        )
    }

    @Test
    fun foodRuleSilentWithoutPitch() {
        val foodLike = tech(meanLuma = 0.5f, saturation = 0.5f, edgeDensity = 0.3f)
        assertNull(
            PromptEngine.candidateFor("R07_food_angle", signals(tech = foodLike, pitch = null), 0L),
            "没有俯仰角就不猜"
        )
        assertNotNull(
            PromptEngine.candidateFor("R07_food_angle", signals(tech = foodLike, pitch = -10f), 0L),
            "有俯仰角且偏离 45° 才提示"
        )
        assertNull(
            PromptEngine.candidateFor("R07_food_angle", signals(tech = foodLike, pitch = -45f), 0L),
            "已经在 45° 附近就不打扰"
        )
    }

    @Test
    fun teleAbuseSuppressedByCoveredTopic() {
        val dim = tech(meanLuma = 0.2f)
        assertNull(
            PromptEngine.candidateFor(
                "R08_tele_abuse",
                signals(tech = dim, hasTele = true, equivFocalMm = 70, coveredTopics = setOf("tele")),
                0L
            ),
            "FeatureAdvisor 已经讲过 tele 就闭嘴"
        )
        assertNotNull(
            PromptEngine.candidateFor(
                "R08_tele_abuse",
                signals(tech = dim, hasTele = true, equivFocalMm = 70),
                0L
            ),
            "没人讲过才轮到提示层说"
        )
    }

    @Test
    fun moveGuideUsesPlanner() {
        val candidate = PromptEngine.candidateFor(
            "R09_move_guide",
            signals(tab = PromptEngine.TAB_VIDEO, videoIntentOrdinal = 0),
            16_000L
        )
        assertNotNull(candidate)
        assertTrue(
            VideoMovePlanner.poolFor(0).contains(candidate!!.tipId),
            "tipId 必须来自 WALK 的运镜池"
        )
        // 运镜属于「当前场景强相关」而不是通用教学轮播：
        // 必须是 CONTEXT，否则 S8 的默认档位 STANDARD 会把整条规则过滤掉。
        assertEquals(PromptPriority.CONTEXT, candidate.priority)
    }

    @Test
    fun moveGuideRespectsEightSecondBeat() {
        // lastGuideAt 由调度器回填，这里直接走 evaluate 验证节拍
        val videoSignals = signals(tab = PromptEngine.TAB_VIDEO, videoIntentOrdinal = 0)
        val justShown = PromptEngine.evaluate(videoSignals, 10_000L, lastGuideAt = 5_000L)
        assertTrue(justShown.none { it.ruleId == "R09_move_guide" }, "8s 节拍内不出运镜")

        val due = PromptEngine.evaluate(videoSignals, 13_001L, lastGuideAt = 5_000L)
        assertTrue(due.any { it.ruleId == "R09_move_guide" }, "8s 到了才出")
    }

    @Test
    fun rankingIsDeterministic() {
        val sample = signals(tech = tech(meanLuma = 0.12f, highlightClip = 0.3f), roll = 6f)
        val first = PromptEngine.evaluate(sample, 20_000L).map { "${it.ruleId}:${it.priority}" }
        repeat(100) {
            val again = PromptEngine.evaluate(sample, 20_000L).map { "${it.ruleId}:${it.priority}" }
            assertEquals(first, again, "同输入必须同输出")
        }
    }

    @Test
    fun noTipIdAppearsTwiceInOneEvaluation() {
        // 夜景 + 暗场：R03（urgent）与 R06（context）都会指向 night_handheld_static
        val sample = signals(tech = tech(meanLuma = 0.10f), scene = SceneType.NIGHT)
        val result = PromptEngine.evaluate(sample, 20_000L)
        val tipIds = result.map { it.tipId }
        assertEquals(tipIds.size, tipIds.distinct().size, "同一条技巧不能同时出现在两个候选里")
        // 去重后应保留优先级更高的 R03
        assertTrue(result.any { it.ruleId == "R03_underexposed" && it.tipId == "night_handheld_static" })
        assertFalse(result.any { it.ruleId == "R06_night_low_light" && it.tipId == "night_handheld_static" })
    }

    @Test
    fun videoTabSilencesPhotoRules() {
        val sample = signals(tech = tech(meanLuma = 0.1f, highlightClip = 0.4f), roll = 8f, tab = PromptEngine.TAB_VIDEO)
        val result = PromptEngine.evaluate(sample, 20_000L)
        val photoRules = setOf(
            "R01_tilt", "R02_overexposed", "R03_underexposed", "R04_backlit_face",
            "R05_portrait_wide", "R06_night_low_light", "R07_food_angle", "R08_tele_abuse"
        )
        assertFalse(result.any { it.ruleId in photoRules }, "录像页不该出现拍照规则")
    }

    @Test
    fun photoTabSilencesMoveRule() {
        val sample = signals(tech = tech(meanLuma = 0.5f), roll = 0f, tab = PromptEngine.TAB_PHOTO)
        val result = PromptEngine.evaluate(sample, 20_000L)
        assertFalse(result.any { it.ruleId == "R09_move_guide" })
    }

    @Test
    fun gridRuleFiresOnlyWhenGridOff() {
        assertNotNull(PromptEngine.candidateFor("R11_grid_off", signals(gridOn = false), 0L))
        assertNull(PromptEngine.candidateFor("R11_grid_off", signals(gridOn = true), 0L))
        assertEquals(
            "grid",
            PromptEngine.candidateFor("R11_grid_off", signals(gridOn = false), 0L)!!.recoverKey,
            "用户打开网格后应自动收敛"
        )
    }

    @Test
    fun fallbackGuideNeedsIdleAndStableScene() {
        val calm = signals(tech = tech(meanLuma = 0.5f), roll = 0f, stability = 0.05f)
        // 空闲够久 → 出兜底
        assertTrue(PromptEngine.evaluate(calm, 20_000L, lastCandidateAt = 1_000L).any { it.ruleId == "R10_fallback_guide" })
        // 刚有硬候选 → 不出
        assertFalse(PromptEngine.evaluate(calm, 20_000L, lastCandidateAt = 19_000L).any { it.ruleId == "R10_fallback_guide" })
        // 画面在抖 → 不谈教学
        val shaky = signals(tech = tech(meanLuma = 0.5f), roll = 0f, stability = 0.9f)
        assertFalse(PromptEngine.evaluate(shaky, 20_000L, lastCandidateAt = 1_000L).any { it.ruleId == "R10_fallback_guide" })
    }

    @Test
    fun missingTipIdsAreSkippedSilently() {
        // 传空集合模拟"技法库里一条都查不到"：引擎必须一声不吭而不是崩溃。
        // 生产环境传的是 `TechniqueBookV15.pushEligibleIds`（83 条去掉 8 条禁推）。
        val sample = signals(tech = tech(meanLuma = 0.1f, highlightClip = 0.4f), roll = 8f, gridOn = false)
        val result = PromptEngine.evaluate(sample, 20_000L, availableTipIds = emptySet())
        assertTrue(result.isEmpty(), "找不到 tipId 就必须静默跳过")
    }

    /**
     * R11 的第二道门（QA P1-3）：刚关掉的网格不该被立刻要求打开。
     *
     * 用户关网格通常是为了看清画面。若下一 tick 就弹「网格关着」，
     * 等于跟他的显式选择对着干；而且他一打开就会被 `autoResolve` 永久拉黑 ——
     * 这条提示只剩一次惹人烦的机会。
     */
    @Test
    fun gridRuleWaitsForTheQuietPeriod() {
        // 刚关 0 秒：不打扰
        assertNull(
            PromptEngine.candidateFor("R11_grid_off", signals(gridOn = false, gridOffAgoMs = 0L), 0L),
            "刚关掉就提醒 = 跟用户的显式选择对着干"
        )
        assertNull(
            PromptEngine.candidateFor(
                "R11_grid_off",
                signals(gridOn = false, gridOffAgoMs = PromptEngine.GRID_OFF_QUIET_MS - 1),
                0L
            ),
            "静默期边界内（59999ms）也不许出"
        )
        // 长期关着（静默期已过）：温和提醒一次
        assertNotNull(
            PromptEngine.candidateFor(
                "R11_grid_off",
                signals(gridOn = false, gridOffAgoMs = PromptEngine.GRID_OFF_QUIET_MS + 1),
                0L
            ),
            "关了一分钟以上才该提醒"
        )
        // 开着的时候永远不说话
        assertNull(
            PromptEngine.candidateFor(
                "R11_grid_off",
                signals(gridOn = true, gridOffAgoMs = Long.MAX_VALUE),
                0L
            ),
            "网格开着就没什么可说的"
        )
        // 静默期的绝对值必须钉住：上面的用例用的是「常量 ±1」的相对边界，
        // 常量被改成 0 时它们依然全绿，无法发现静默期被悄悄取消。
        assertEquals(
            60_000L,
            PromptEngine.GRID_OFF_QUIET_MS,
            "静默期必须是 60 秒：太短会跟用户的关闭动作对着干，太长等于没有这条提示"
        )
    }

    @Test
    fun gridRuleTextDoesNotContradictTheUser() {
        val candidate = PromptEngine.candidateFor(
            "R11_grid_off",
            signals(gridOn = false, gridOffAgoMs = 90_000L),
            0L
        )
        assertNotNull(candidate)
        // 文案不能是命令句"把九宫格打开"——用户刚自己关的
        assertFalse(
            candidate.action.contains("打开"),
            "R11 文案不该命令用户撤销自己刚做的选择：${candidate!!.action}"
        )
        assertTrue(candidate.action.contains("网格"), "应当陈述事实而不是下命令")
    }

    /**
     * 节奏标记（QA P1-1）：只有 R09/R10 是 `paced`。
     *
     * 调度器按这个标记回填 `lastGuideAt`，不再按 `priority == GUIDE` 判 ——
     * R09 已经升到 CONTEXT，按优先级判会把 8 秒节拍门变成死逻辑。
     */
    @Test
    fun onlyMoveAndFallbackRulesAreMarkedPaced() {
        val move = PromptEngine.candidateFor(
            "R09_move_guide",
            signals(tab = PromptEngine.TAB_VIDEO, videoIntentOrdinal = 0),
            16_000L
        )
        assertNotNull(move)
        assertTrue(move!!.paced, "R09 运镜必须是节奏类")

        val fallback = PromptEngine.candidateFor(
            "R10_fallback_guide",
            signals(tech = tech(meanLuma = 0.5f), roll = 0f, stability = 0.05f),
            20_000L
        )
        assertNotNull(fallback)
        assertTrue(fallback!!.paced, "R10 兜底轮播必须是节奏类")

        val tilt = PromptEngine.candidateFor("R01_tilt", signals(roll = 8f), 0L)
        assertNotNull(tilt)
        assertFalse(tilt!!.paced, "硬伤提示不该推进节拍")
    }

    @Test
    fun videoMovePoolsAreWellFormed() {
        assertEquals(8, VideoMovePlanner.intentCount, "8 个录像意图各有一个池")
        for (intent in 0 until VideoMovePlanner.intentCount) {
            val pool = VideoMovePlanner.poolFor(intent)
            assertTrue(pool.isNotEmpty(), "意图 $intent 的池不能为空")
            assertEquals(pool.size, pool.distinct().size, "池内不该有重复")
            assertTrue(pool.all { it.startsWith("move_") }, "运镜 id 必须以 move_ 开头")
        }
        assertNull(VideoMovePlanner.poolFor(99).firstOrNull(), "越界意图返回 null 而不是崩溃")
        assertNull(VideoMovePlanner.nextMove(99, 0))
        assertNotNull(VideoMovePlanner.nextMove(0, 1))
    }
}

/** 把 ScoreBoard 的换算常量转出来，避免测试里重复魔法数字。 */
private object ScoreBoardThresholds {
    const val CLIP_HIGH_CONVERTED = com.shottuner.app.analysis.ScoreBoard.CLIP_HIGH_CONVERTED
}
