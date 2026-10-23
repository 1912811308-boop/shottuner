package com.shottuner.app.knowledge

import com.shottuner.app.model.SceneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotographyBookTest {

    @Test
    fun entriesAreEnoughAndIdsUnique() {
        val all = PhotographyBook.all
        assertTrue("技法条目至少 26 条，实际 ${all.size}", all.size >= 26)
        assertEquals("id 必须唯一", all.size, all.map { it.id }.distinct().size)
    }

    @Test
    fun allCategoriesCovered() {
        // 断言原样保留。v1.5 起 `PhotographyBook.all` 就是「39 + 44」的合并库，
        // 所以新增的 4 个分类（自拍/运镜/参数/避坑）会被这条用例自动覆盖，
        // 不需要在这里加任何分支。
        for (category in TechCategory.values()) {
            assertTrue(
                "分类 ${category.label} 至少要有 1 条",
                PhotographyBook.all.any { it.category == category }
            )
        }
    }

    @Test
    fun textFieldsAreReasonable() {
        val problems = ArrayList<String>()
        for (tech in PhotographyBook.all) {
            val id = tech.id
            fun check(ok: Boolean, message: String) {
                if (!ok) problems.add("$id $message")
            }
            check(tech.title.length in 8..16, "title 长度 ${tech.title.length}")
            check(tech.oneLiner.length in 4..20, "oneLiner 长度 ${tech.oneLiner.length}")
            check(tech.whenTo.length in 40..80, "whenTo 长度 ${tech.whenTo.length}")
            check(tech.why.length in 60..120, "why 长度 ${tech.why.length}")
            check(tech.steps.size in 3..6, "steps 数量 ${tech.steps.size}")
            check(tech.mistakes.isNotEmpty(), "mistakes 不能为空")
            check(tech.check.length in 20..70, "check 长度 ${tech.check.length}")
            check(tech.params.isNotEmpty(), "params 不能为空")
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun universalEntriesDoNotRequireTele() {
        for (tech in PhotographyBook.all) {
            val universal = tech.trigger.scenes.isEmpty()
            assertFalse(
                "${tech.id} 不能既是通用条目又要求长焦",
                universal && tech.trigger.requiresTele
            )
        }
    }

    @Test
    fun suggestWithoutStatsReturnsThreeBasic() {
        val result = TechniqueMatcher.suggest(
            scene = SceneType.GENERAL,
            signals = TechSignals(hasStats = false)
        )
        assertEquals(3, result.size)
        assertTrue(result.all { it.category == TechCategory.BASIC })
    }

    @Test
    fun typicalScenesGetThreeDistinctCategories() {
        val night = TechniqueMatcher.suggest(
            scene = SceneType.NIGHT,
            signals = TechSignals(
                meanLuma = 0.12f,
                highlightClip = 0.02f,
                dynamicRange = 0.5f,
                supportsManual = true,
                hasStats = true
            )
        )
        assertEquals(3, night.size)
        assertEquals(3, night.map { it.category }.distinct().size)

        val backlit = TechniqueMatcher.suggest(
            scene = SceneType.BACKLIT,
            signals = TechSignals(
                meanLuma = 0.55f,
                highlightClip = 0.12f,
                skinRatio = 0.18f,
                centerBias = -0.12f,
                hasStats = true
            )
        )
        assertEquals(3, backlit.size)
        assertEquals(3, backlit.map { it.category }.distinct().size)

        val landscape = TechniqueMatcher.suggest(
            scene = SceneType.LANDSCAPE,
            signals = TechSignals(
                meanLuma = 0.5f,
                dynamicRange = 0.6f,
                saturation = 0.4f,
                edgeDensity = 0.3f,
                hasStats = true
            )
        )
        assertEquals(3, landscape.size)
        assertEquals(3, landscape.map { it.category }.distinct().size)
    }
}
