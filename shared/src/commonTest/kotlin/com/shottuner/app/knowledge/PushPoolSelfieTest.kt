package com.shottuner.app.knowledge

import com.shottuner.app.model.SceneType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「自拍 5 条能不能进推送池」的回归（3.2.0 前置通路）。
 *
 * ## 背景
 * 加前置之前，`pushEligibleIds` 把 `SELFIE_IDS` 静态减掉了 —— 那时没有前置通路，
 * 推「手臂伸直自拍」是荒谬的。现在能翻到前置，这条排除必须变成**运行时条件**：
 * 后置时不推、前置时推。这一组用例守的就是这个"条件"。
 *
 * ## 三条不变量（任何情况下都不许破）
 * 1. 后置池（`includeSelfie = false`）里没有自拍；
 * 2. 前置池（`includeSelfie = true`）里正好多出这 5 条，不多不少；
 * 3. `BROWSE_ONLY_IDS` 那 3 条**两个池子都不许有**（它们缺的是"能判定的信号"，
 *    跟镜头方向无关，前置也救不了）。
 *
 * 这里刻意**不**断言"自拍条目会被真的推出来"：目前 `PromptEngine` 没有任何规则点名
 * 自拍，进池只是让它有资格被 R10 兜底轮播选中。断言"一定推出"会把测试绑死在规则表的
 * 实现细节上，规则一改就红，而红的原因跟本功能无关。
 */
class PushPoolSelfieTest {

    // ── 池子本身 ──────────────────────────────────────────────────────────────

    @Test
    fun defaultPoolExcludesSelfie() {
        val pool = TechniqueBookV15.pushEligibleIds(includeSelfie = false)
        assertTrue(
            (pool intersect TechniqueBookV15.SELFIE_IDS).isEmpty(),
            "后置时自拍 5 条不得进推送池"
        )
        assertEquals(
            TechniqueBookV15.byId.keys - TechniqueBookV15.BROWSE_ONLY_IDS - TechniqueBookV15.SELFIE_IDS,
            pool,
            "后置池 = 全库 − 仅浏览 − 自拍"
        )
        assertEquals(
            TechniqueBookV15.byId.size - TechniqueBookV15.BROWSE_ONLY_IDS.size -
                TechniqueBookV15.SELFIE_IDS.size,
            pool.size,
            "后置池条数 = 83 − 3 − 5"
        )
    }

    @Test
    fun includeSelfiePoolAddsExactlyTheFiveSelfieIds() {
        val back = TechniqueBookV15.pushEligibleIds(includeSelfie = false)
        val front = TechniqueBookV15.pushEligibleIds(includeSelfie = true)

        assertTrue(
            TechniqueBookV15.SELFIE_IDS.all { it in front },
            "前置时自拍 5 条必须全部进池，漏一条就等于前置白加了"
        )
        assertEquals(
            back.size + TechniqueBookV15.SELFIE_IDS.size,
            front.size,
            "前置池 = 后置池 + 5 条，不多不少"
        )
        assertEquals(
            front - back,
            TechniqueBookV15.SELFIE_IDS,
            "两个池子的差集必须恰好是自拍 5 条（多出任何一条都说明有别的条件被带进来了）"
        )
    }

    @Test
    fun browseOnlyIdsNeverEnterAnyPool() {
        val browseOnly = TechniqueBookV15.BROWSE_ONLY_IDS
        assertTrue(
            (TechniqueBookV15.pushEligibleIds(includeSelfie = false) intersect browseOnly).isEmpty(),
            "仅浏览条目不得进后置池"
        )
        assertTrue(
            (TechniqueBookV15.pushEligibleIds(includeSelfie = true) intersect browseOnly).isEmpty(),
            "仅浏览条目不得进前置池 —— 它们缺的是信号，不是镜头"
        )
    }

    @Test
    fun legacyPropertyStillMeansBackCameraPool() {
        // 旧的 `val pushEligibleIds` 保留给既有调用点（测试、诊断），语义必须是"后置口径"。
        // 加了参数之后悄悄改掉老调用点的含义，是最典型的"改一处坏一片"。
        assertEquals(
            TechniqueBookV15.pushEligibleIds(includeSelfie = false),
            TechniqueBookV15.pushEligibleIds,
            "无参版本必须等价于 includeSelfie = false"
        )
    }

    @Test
    fun everyIdInThePoolsReallyExistsInTheBook() {
        for (includeSelfie in listOf(true, false)) {
            val pool = TechniqueBookV15.pushEligibleIds(includeSelfie = includeSelfie)
            val missing = pool.filter { TechniqueBookV15.byId[it] == null }
            assertTrue(missing.isEmpty(), "池子里出现了技法库没有的 id：$missing")
        }
    }

    // ── 调度器：不换实例、只换池子 ────────────────────────────────────────────

    private fun signals(): PromptSignals = PromptSignals(
        tech = TechSignals(meanLuma = 0.5f, hasStats = true),
        roll = 0f,
        gridOn = true,
        gridOffAgoMs = Long.MAX_VALUE,
        tab = PromptEngine.TAB_PHOTO,
        videoIntentOrdinal = -1,
        stability = 0f,
        scene = SceneType.GENERAL
    )

    private fun candidate(tipId: String): PromptCandidate = PromptCandidate(
        ruleId = "R_$tipId",
        tipId = tipId,
        priority = PromptPriority.CONTEXT,
        weight = 20,
        action = "动作",
        reason = "理由"
    )

    /**
     * 翻到后置时，屏幕上那条"只有前置才成立"的提示必须**立刻撤掉**。
     *
     * 这是 `setAvailableTipIds` 存在的全部理由：只换集合、不管当前这条的话，
     * 用户会在后置取景上继续看到「手臂伸直再拍」，直到它自然超时 ——
     * 那时提示和画面已经对不上了。
     *
     * 为什么第二次 tick 喂的是**空候选**：真实路径里 `PromptEngine.evaluate` 会按
     * `availableTipIds` 把不在池里的 id 过滤掉，被过滤的条目根本到不了状态机。
     * 所以空列表就是"evaluate 过滤后的结果"。要验证的是：状态机不能因为"这一帧没候选"
     * 就把上一帧的卡继续挂着 —— 撤卡必须发生在换池子那一刻。
     */
    @Test
    fun swappingPoolDropsCardThatLeftThePool() {
        val selfie = TechniqueBookV15.SELFIE_IDS.first()
        val scheduler = PromptScheduler(
            initialTipIds = TechniqueBookV15.pushEligibleIds(includeSelfie = true)
        )
        val card = candidate(selfie)

        val shown = scheduler.tickWithCandidates(listOf(card), signals(), 1_000L)
        assertEquals(selfie, shown.prompt?.tipId, "前置池里有这条，应该能出牌")

        // 翻到后置
        scheduler.setAvailableTipIds(TechniqueBookV15.pushEligibleIds(includeSelfie = false))

        val after = scheduler.tickWithCandidates(emptyList(), signals(), 2_000L)
        assertNull(after.prompt, "这条已不在池里，槽位必须空")
        assertTrue(after.changed, "撤卡要让 UI 知道（changed = true），否则槽位会停着不收")
    }

    /** 反过来：翻到前置不该打掉一条本来就在池里的提示（否则每次翻转都会闪一下空槽）。 */
    @Test
    fun swappingPoolKeepsCardThatStaysInThePool() {
        val common = TechniqueBookV15.pushEligibleIds(includeSelfie = false).first()
        val scheduler = PromptScheduler(
            initialTipIds = TechniqueBookV15.pushEligibleIds(includeSelfie = false)
        )
        val card = candidate(common)

        val shown = scheduler.tickWithCandidates(listOf(card), signals(), 1_000L)
        assertEquals(common, shown.prompt?.tipId)

        scheduler.setAvailableTipIds(TechniqueBookV15.pushEligibleIds(includeSelfie = true))

        val after = scheduler.tickWithCandidates(listOf(card), signals(), 2_000L)
        assertNotNull(after.prompt, "这条前后都在池里，不该被撤")
        assertEquals(common, after.prompt?.tipId)
    }
}
