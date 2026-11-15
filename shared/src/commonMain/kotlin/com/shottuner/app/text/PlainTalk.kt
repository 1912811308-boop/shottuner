package com.shottuner.app.text

import kotlin.math.abs

/**
 * 人话层：把「测出来的数」翻译成「小白看得懂的下一步动作」。
 *
 * ## 为什么要有这个文件
 *
 * 主屏原来印的是这些：
 * - `亮度 42% · 高光截断 8%`
 * - `ISO 400 · 1/60 · 3200K · EV +0.3`
 *
 * 对一个**不会拍照的人**，这两行都是**术语**，不是信息。他知道「高光截断 8%」这个词，
 * 但不知道 8% 算不算严重、更不知道该动哪个拨盘。结果是：屏幕上信息很多，
 * 而他拍出来的照片没有任何变化。
 *
 * 所以这里只做一件事：**把判断和动作说到前面，把数字留到后面**。
 * - 先说结论：「有点暗，提亮一点」
 * - 数字降级为补充：`（亮度 42%）`
 *
 * ## 三条纪律（改这个文件前先读）
 *
 * 1. **只用「提亮/压暗/往左/靠近」这种动作词**，不用 EV / 档 / 截断 / 直方图。
 *    术语可以出现在括号里，不能出现在结论里。
 * 2. **判断的方向必须与参数建议同源**：这里的亮度判断读的是 `evDelta`
 *    （与 `ParamRecommender` 同一个量），不是另算一套阈值。
 *    两套阈值一定会分叉，然后出现「上面说亮度正好、下面让你 ISO 加一档」。
 * 3. **不确定就说不确定**：拿不到数据时返回空串，由调用方决定退回显示原始数字，
 *    不要编一个「亮度正常」出来 —— 那是假话，比不显示更糟。
 *
 * ## 这些文案"对不对"由谁保证
 *
 * 只有真机试拍能保证。所以阈值刻意取得保守（±0.3 EV 才提示「有点」，
 * ±0.7 EV 才说「明显」），宁可少说一句，也不要在画面已经合格时催用户乱拨。
 */
object PlainTalk {

    /**
     * 亮度的结论 + 动作。
     *
     * @param evDelta 还差多少 EV 到目标亮度。**正数 = 画面偏暗、要提亮**。
     *   与 `Recommendation.evDelta` 同一个量，别自己再算一套。
     * @param highlightClip 高光截断比例（0..1）。超过 0.10 说明有面积不小的区域已经糊成纯白，
     *   这时候即使 evDelta 很小也要先压暗 —— 糊掉的部分救不回来。
     * @param shadowClip 暗部截断比例（0..1）。超过 0.45 说明暗部大面积死黑。
     * @return 一句可直接执行的话。**永不为空**（evDelta 是必给的量）。
     */
    fun brightness(
        evDelta: Float,
        highlightClip: Float = 0f,
        shadowClip: Float = 0f
    ): String = when {
        // 糊掉优先：过曝/欠曝的像素修不回来，先处理它，别看 evDelta
        highlightClip > 0.10f -> "亮的地方已经糊了，压暗一点"
        shadowClip > 0.45f -> "暗的地方糊成一片，提亮一点"
        evDelta > 0.7f -> "太暗了，提亮"
        evDelta > 0.3f -> "有点暗，提亮一点"
        evDelta < -0.7f -> "太亮了，压暗"
        evDelta < -0.3f -> "有点亮，压暗一点"
        else -> "亮度正好"
    }

    /**
     * 亮度判断是否已经到了「不用动」的程度。
     *
     * 给 UI 决定要不要把这条结论染绿（只用文字会有歧义：
     * 「亮度正好」和「太暗了」在灰色小字里长得一样）。
     */
    fun brightnessIsFine(evDelta: Float, highlightClip: Float = 0f, shadowClip: Float = 0f): Boolean =
        abs(evDelta) <= 0.3f && highlightClip <= 0.10f && shadowClip <= 0.45f

    /**
     * 水平：地平线/建筑竖边歪了多少。
     *
     * @param rollDeg 横滚角（度）。正 = 顺时针歪。
     * @return 空白表示「没读到传感器」，调用方应回到原始提示，不要编一个「水平」。
     */
    fun level(isLevel: Boolean, rollDeg: Float, hasReading: Boolean = true): String {
        if (!hasReading) return ""
        if (isLevel) return "水平了，按快门就行"
        // 手机顺时针歪了，要让画面正过来就得**逆时针**回，所以提示「往左抬」
        val dir = if (rollDeg > 0f) "往左抬一点" else "往右抬一点"
        val deg = abs(rollDeg)
        // 1° 以内人眼看不出，别拿 0.4° 去催人；超出就直接给数，让"歪多少"可判断
        return if (deg < 1f) "快平了，微调一下" else "歪了约 ${deg.toInt()}°，$dir"
    }

    /**
     * 参数：一句话说清「现在该干什么」，术语退到括号里。
     *
     * ★ 刻意**不**在结论里提 ISO / 快门 / 色温 / EV：不会拍照的人不知道这些词该往哪拨。
     *   具体档位仍然给（那是产品的立身之本），但放在后半句，让需要的人能照抄。
     */
    fun params(
        evDelta: Float,
        iso: Int,
        shutterLabel: String,
        kelvin: Int,
        needsTripod: Boolean
    ): String {
        // 需要三脚架时先说这个 —— 它比任何参数都重要（手持长曝一定糊）
        if (needsTripod) {
            return "光太暗了：找个地方靠住手，或者把手机放稳（$iso · $shutterLabel）"
        }
        val action = when {
            evDelta > 0.7f -> "把亮度提上来"
            evDelta > 0.3f -> "稍微提亮"
            evDelta < -0.7f -> "把亮度压下去"
            evDelta < -0.3f -> "稍微压暗"
            else -> "不用调，直接拍"
        }
        return "$action · $iso · $shutterLabel · ${kelvin}K"
    }
}
