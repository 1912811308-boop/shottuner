package com.shottuner.app.knowledge

import com.shottuner.app.model.SceneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 匹配器的三条 v1.5 约束 + 新分类文案。
 *
 * 这一层最容易出的错不是崩，而是「悄悄多给一条不该给的」：
 * 拍照页冒出运镜、后摄画面冒出「手臂伸直自拍」。所以断言都写成
 * 「池子里不许出现 X」而不是「池子里应该有 Y」。
 */
class TechniqueMatcherTest {

    // ── 页签隔离 ───────────────────────────────────────────────────────────────

    @Test
    fun photoPoolExcludesCameraMove() {
        val pool = TechniqueMatcher.poolFor(PromptEngine.TAB_PHOTO)
        assertTrue("拍照页候选池不能为空", pool.isNotEmpty())
        val moves = pool.filter { it.category == TechCategory.CAMERA_MOVE }
        assertTrue("拍照页不该出现运镜条目：${moves.map { it.id }}", moves.isEmpty())
    }

    @Test
    fun videoPoolOnlyAllowsMoveOrUniversal() {
        val pool = TechniqueMatcher.poolFor(PromptEngine.TAB_VIDEO)
        assertTrue("录像页候选池不能为空", pool.isNotEmpty())
        val offenders = pool.filter { tech ->
            tech.category != TechCategory.CAMERA_MOVE && tech.trigger.scenes.isNotEmpty()
        }
        assertTrue(
            "录像页只允许运镜 + 通用条目，这些是场景专用条：${offenders.map { it.id }}",
            offenders.isEmpty()
        )
        assertTrue(
            "录像页必须包含运镜条目",
            pool.any { it.category == TechCategory.CAMERA_MOVE }
        )
    }

    @Test
    fun suggestRespectsTabBoundary() {
        val signals = TechSignals(
            meanLuma = 0.5f,
            skinRatio = 0.20f,
            saturation = 0.4f,
            edgeDensity = 0.3f,
            hasTele = true,
            hasStats = true
        )
        val photo = TechniqueMatcher.suggest(SceneType.PORTRAIT, signals, tab = PromptEngine.TAB_PHOTO)
        assertFalse(
            "拍照页建议里不能有运镜",
            photo.any { it.category == TechCategory.CAMERA_MOVE }
        )

        val video = TechniqueMatcher.suggest(SceneType.PORTRAIT, signals, tab = PromptEngine.TAB_VIDEO)
        for (tech in video) {
            assertTrue(
                "录像页建议只能是运镜或通用条，拿到 ${tech.id}",
                tech.category == TechCategory.CAMERA_MOVE || tech.trigger.scenes.isEmpty()
            )
        }
    }

    @Test
    fun noSelfieOrBrowseOnlyTechIsEverSuggested() {
        val banned = TechniqueBookV15.SELFIE_IDS + TechniqueBookV15.BROWSE_ONLY_IDS
        val signals = TechSignals(
            meanLuma = 0.12f,
            skinRatio = 0.25f,
            saturation = 0.5f,
            edgeDensity = 0.3f,
            hasTele = true,
            hasStats = true
        )
        for (tab in listOf(PromptEngine.TAB_PHOTO, PromptEngine.TAB_VIDEO)) {
            for (scene in SceneType.values()) {
                val picked = TechniqueMatcher.suggest(scene, signals, limit = 8, tab = tab)
                for (tech in picked) {
                    assertFalse("$tab/$scene 推了 ${tech.id}", tech.id in banned)
                }
            }
        }
    }

    // ── 新分类的 reasonFor ────────────────────────────────────────────────────

    @Test
    fun newCategoriesGetSpecificReasonNotGeneric() {
        val generic = "适合当前画面的通用提醒"
        val noSignal = TechSignals(hasStats = true)

        val move = techniqueOf("move_elbow_tucked")
        val moveReason = TechniqueMatcher.reasonFor(move, noSignal)
        assertEquals("运镜条的兜底文案", "运镜先解决稳，再谈好看", moveReason)
        assertFalse("运镜条不能退化成通用提醒", moveReason == generic)

        val params = techniqueOf("params_white_balance_mood")
        assertEquals("参数条的兜底文案", "按这套起手参数去拨", TechniqueMatcher.reasonFor(params, noSignal))

        val pitfall = techniqueOf("pitfall_grid_on")
        assertEquals(
            "避坑条的兜底文案",
            "这条是习惯问题，改一次长期受益",
            TechniqueMatcher.reasonFor(pitfall, noSignal)
        )

        val selfie = techniqueOf("selfie_arm_extended")
        assertEquals(
            "自拍条在没肤色时给分类文案",
            "自拍专用的距离和机位",
            TechniqueMatcher.reasonFor(selfie, noSignal)
        )
    }

    @Test
    fun reasonForUsesRealSignalsWhenAvailable() {
        // 有肤色时应该说出「画面里有肤色」，而不是分类兜底
        val selfie = techniqueOf("selfie_arm_extended")
        val withSkin = TechSignals(skinRatio = 0.20f, hasStats = true)
        assertEquals("画面里有肤色", TechniqueMatcher.reasonFor(selfie, withSkin))

        // 夜景条有高光溢出时应该点出溢出的百分比
        val nightTech = techniqueOf("night_protect_highlights")
        val blown = TechSignals(highlightClip = 0.25f, hasStats = true)
        val reason = TechniqueMatcher.reasonFor(nightTech, blown)
        assertTrue("应包含高光溢出比例，实际：$reason", reason.contains("高光溢出"))
        assertTrue("应包含具体百分比，实际：$reason", reason.contains("25%"))
    }

    // ── v1.5 新增的两个触发条件 ────────────────────────────────────────────────

    @Test
    fun shadowClipConditionGatesTheEntry() {
        val dark = synthetic(minShadowClip = 0.30f)
        val signals = TechSignals(meanLuma = 0.5f, shadowClip = 0.10f, hasStats = true)
        assertNull(
            "暗部没死黑时不该命中",
            TechniqueMatcher.scoreOf(dark, SceneType.GENERAL, signals)
        )

        val veryDark = TechSignals(meanLuma = 0.5f, shadowClip = 0.45f, hasStats = true)
        val score = TechniqueMatcher.scoreOf(dark, SceneType.GENERAL, veryDark)
        assertNotNull("暗部死黑时必须命中", score)
        assertTrue("命中后要吃掉条件加分", score!! > dark.trigger.weight)

        // 反向：两个方向都要在文案里说得出来
        assertTrue(
            TechniqueMatcher.reasonFor(dark, veryDark).contains("暗部死黑")
        )
    }

    @Test
    fun highlightClipCapConditionGatesTheEntry() {
        val capped = synthetic(maxHighlightClip = 0.10f)

        val tooBright = TechSignals(meanLuma = 0.6f, highlightClip = 0.30f, hasStats = true)
        assertNull(
            "高光已经溢出时不该再谈「压得住高光」",
            TechniqueMatcher.scoreOf(capped, SceneType.GENERAL, tooBright)
        )

        val safe = TechSignals(meanLuma = 0.6f, highlightClip = 0.02f, hasStats = true)
        assertNotNull(
            "高光还压得住时应该命中",
            TechniqueMatcher.scoreOf(capped, SceneType.GENERAL, safe)
        )
        assertTrue(TechniqueMatcher.reasonFor(capped, safe).contains("高光还压得住"))
    }

    @Test
    fun sceneBonusOnlyAddsNeverFilters() {
        val universal = synthetic()
        val inScene = TechniqueMatcher.scoreOf(universal, SceneType.NIGHT, TechSignals(hasStats = true))
        val outScene = TechniqueMatcher.scoreOf(universal, SceneType.GENERAL, TechSignals(hasStats = true))
        assertEquals(
            "通用条在任何场景都应命中，且得分相同",
            inScene,
            outScene
        )

        val nightOnly = techniqueOf("night_iso_ceiling")
        val nightSignals = TechSignals(meanLuma = 0.12f, hasStats = true)
        val boosted = TechniqueMatcher.scoreOf(nightOnly, SceneType.NIGHT, nightSignals)
        val plain = TechniqueMatcher.scoreOf(nightOnly, SceneType.GENERAL, nightSignals)
        assertNotNull(boosted)
        assertNotNull(plain)
        assertTrue("命中场景要加分", boosted!! > plain!!)
    }

    // ── 小工具 ─────────────────────────────────────────────────────────────────

    private fun techniqueOf(id: String): Technique {
        val tech = TechniqueBookV15.byId[id]
        assertNotNull("技法库里没有 $id", tech)
        return tech!!
    }

    private fun synthetic(
        minShadowClip: Float? = null,
        maxHighlightClip: Float? = null
    ): Technique = Technique(
        id = "synthetic_probe",
        title = "测试用合成条目",
        category = TechCategory.BASIC,
        oneLiner = "仅用于单测",
        whenTo = "仅用于单测，不出现在产品文案里",
        steps = listOf("a", "b", "c"),
        params = listOf("k" to "v"),
        why = "仅用于单测，不出现在产品文案里",
        mistakes = listOf("仅用于单测"),
        check = "仅用于单测，不出现在产品文案里",
        trigger = TechTrigger(
            weight = 10,
            minShadowClip = minShadowClip,
            maxHighlightClip = maxHighlightClip
        )
    )
}
