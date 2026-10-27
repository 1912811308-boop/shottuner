package com.shottuner.app.growth

import com.shottuner.app.analysis.ScoreDimension

/**
 * 技法图鉴里的一条（G3）——**纯 JVM**。
 *
 * 本文件不 import 任何 `android.*`，因此可以在 `src/test` 下直接构造与断言。
 *
 * ## 为什么内容与知识库分开
 * [TechniqueDex] 的 83 条是**从 `PhotographyBook.all` 逐条投影出来的**，
 * 不是另抄一份文案。图鉴要展示的东西（标题、一句话解释、归属维度）都取自
 * `Technique` 的既有字段，只有 `dimension` 是我们新加的映射。
 * 好处是：将来知识库改文案 / 加条目，图鉴自动跟上，不存在两份会走样的副本。
 *
 * ## 为什么 dimension 可空
 * 四维（水平 / 曝光 / 构图 / 清晰）是 `ScoreBoard` 的口径，只覆盖"照片画面"
 * 这一层。自拍（本 App 没有前置通路）、运镜（录像）、参数起步值、避坑（设置与习惯）
 * 这四类技法**不落在任何一维上**，硬塞一维等于编造一个测不到的达标线。
 * 因此它们的 `dimension` 为 null，且**不可解锁**（见 `shouldUnlock`）。
 */
data class DexEntry(
    /** 技法 id，与 `Technique.id` 同源，也是 `Recommendation.tipId` 的取值。 */
    val id: String,
    /** 图鉴卡片标题，取自 `Technique.title`。 */
    val title: String,
    /**
     * 一句话解释"这条技法在什么时候用"，取自 `Technique.whenTo`。
     *
     * 刻意**不新写文案**：知识库里那个字段的语义就是"什么情况下用"，
     * 照搬它既省一次内容生产，也保证图鉴与技法页说法一致。
     */
    val body: String,
    /** 归属的评分维度；`null` = 纯知识条目，不参与四维判定。 */
    val dimension: ScoreDimension?,
    /** 来源："v15"（`TechniqueBookV15` 的 44 条）/ "book"（`PhotographyBook.legacy` 的 39 条）。 */
    val source: String
) {
    init {
        require(id.isNotBlank()) { "DexEntry.id 不能为空" }
        require(title.isNotBlank()) { "DexEntry.title 不能为空：$id" }
        require(body.isNotBlank()) { "DexEntry.body 不能为空：$id" }
        require(source == TechniqueDex.SOURCE_V15 || source == TechniqueDex.SOURCE_BOOK) {
            "DexEntry.source 只能是 v15 / book，实际是 $source（id=$id）"
        }
    }
}
