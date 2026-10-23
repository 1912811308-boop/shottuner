package com.shottuner.app.model

/**
 * 构图诊断结果。所有坐标都是 0f..1f 的归一化画面坐标（原点在左上角）。
 *
 * 与 FrameStats 分开的原因：测光统计是逐帧连续的，构图画面的变化慢得多，
 * UI 上按不同频率刷新，混在一起会导致构图提示疯狂跳动。
 */
class CompositionHint(
    /** 构图总分 0..100，用于给用户即时反馈 */
    val score: Int,
    /** 地平线倾角（度）。正值表示画面右侧偏低 */
    val tiltDeg: Float,
    /** 是否检测到可信的地平线 / 水平结构线 */
    val horizonDetected: Boolean,
    /** 地平线所在的纵向位置 0..1 */
    val horizonY: Float,
    /** 主体重心横坐标，0..1 */
    val subjectX: Float,
    /** 主体重心纵坐标，0..1 */
    val subjectY: Float,
    /** 主体显著性置信度 0..1，过低说明画面没有明确主体 */
    val subjectConfidence: Float,
    /** 主体占画面比例 0..1 */
    val subjectSize: Float,
    /** 主体外框左边界，归一化 0..1 */
    val subjectLeft: Float,
    /** 主体外框上边界，归一化 0..1 */
    val subjectTop: Float,
    /** 主体外框右边界，归一化 0..1 */
    val subjectRight: Float,
    /** 主体外框下边界，归一化 0..1；机位教练用它判断脚是否出画、是否切关节 */
    val subjectBottom: Float,
    /** 建议主体落点（三分交点） */
    val targetX: Float,
    val targetY: Float,
    /** 是否有更适合移动到交点的建议 */
    val suggestMove: Boolean,
    /** 按优先级排好的建议，第一条最重要 */
    val tips: List<String>,
    /**
     * 剔除「倾斜」扣分后的构图分，供四维参考分使用。
     *
     * 为什么要单独给一个：倾斜已经被「水平度」扣过一次分了，
     * 如果构图再扣一次，同一处歪斜会被罚两遍，总分不可解释。
     *
     * 默认值引用前序参数，因此原有构造点（[EMPTY]、CompositionAnalyzer 的具名参数构造）
     * 全部源兼容，不需要改动。
     */
    val scoreNoTilt: Int = score
) {
    val primaryTip: String get() = tips.firstOrNull() ?: "构图没问题，可以按快门了"

    companion object {
        val EMPTY = CompositionHint(
            score = 0, tiltDeg = 0f, horizonDetected = false, horizonY = 0.5f,
            subjectX = 0.5f, subjectY = 0.5f, subjectConfidence = 0f, subjectSize = 0f,
            subjectLeft = 0f, subjectTop = 0f, subjectRight = 1f, subjectBottom = 1f,
            targetX = 0.6667f, targetY = 0.3333f, suggestMove = false,
            tips = listOf("把镜头对准要拍的画面")
        )
    }
}
