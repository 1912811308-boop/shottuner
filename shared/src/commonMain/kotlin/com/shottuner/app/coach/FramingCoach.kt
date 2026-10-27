package com.shottuner.app.coach

import com.shottuner.app.model.CompositionHint
import kotlin.math.abs

/**
 * 机位教练的一条结论。
 *
 * severity：0 = 确认可以拍，1 = 建议调整，2 = 必须改（否则成片明显有问题）。
 * code 是规则编号，对应 docs/VIDEO_COACH_SPEC.md 里的 T01/T02/T04/T06，
 * 便于日志与单测定位。
 */
data class FramingAdvice(
    val title: String,
    val reason: String,
    val severity: Int,
    val code: String
)

/** ML Kit 人脸检测结果的精简版。坐标均为 0..1 的归一化画面坐标 */
data class FaceInfo(
    val count: Int,
    val centerX: Float,
    val centerY: Float,
    /** 人脸占画面面积比 */
    val area: Float,
    /** 人脸框底边 y，归一化 */
    val boxBottom: Float,
    /** 头部绕 Y 轴旋转角（度），正值 = 脸朝画面右 */
    val eulerYDeg: Float = 0f
)

/** 人物（或视觉主体）外框，归一化坐标，原点左上、y 向下 */
data class SubjectBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val centerX: Float get() = (left + right) * 0.5f
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
}

/**
 * 人像机位教练（P0-3）：把视频里的取景经验变成可测的几何规则。
 *
 * 刻意做成纯 Kotlin object：不碰 Android API、不持有状态、输入是归一化坐标，
 * 因此可以直接在 app/src/test 里跑 JVM 单测。
 *
 * 常数与 python/shottuner/framing.py 一一对应，不要在这里单独调参；
 * 两边不一致时以 Python 参考实现及其测试为准。
 */
object FramingCoach {

    // ---- 与 framing.py 完全一致的常数 ----
    /** 广角主摄的等效焦距区间：只有落在这里才谈得上边缘透视变形 */
    private const val WIDE_FOCAL_MIN = 18
    private const val WIDE_FOCAL_MAX = 28

    /** 人脸占画面面积低于此值，按全身照处理 */
    private const val FACE_FULL_BODY_MAX = 0.06f

    /** 脸贴边的安全边界（左右各 18%） */
    private const val EDGE_SAFE_X = 0.18f

    /** 全身照里脸中心应该落在的纵向区间 */
    private const val FACE_CENTER_MIN_Y = 0.42f
    private const val FACE_CENTER_MAX_Y = 0.58f

    /** 全身照里脚应该到达的画面底边位置 */
    private const val FOOT_BOTTOM_MIN = 0.88f

    /** 关节禁区：人物外框底边落在这两个区间就是「切在关节上」 */
    private const val WAIST_MIN = 0.44f
    private const val WAIST_MAX = 0.52f
    private const val KNEE_MIN = 0.62f
    private const val KNEE_MAX = 0.72f

    /** 朝向判定：|eulerY| 超过这个角度才算明显侧脸 */
    private const val HEAD_TURN_DEG = 15f

    /**
     * 按优先级返回机位建议，最多两条。
     *
     * @param face 最大人脸；null 或 count<=0 时不做任何机位判断
     * @param subject 人物外框，来自 CompositionHint 的 subjectLeft/Top/Right/Bottom
     * @param comp 构图结果，保留给后续 T03/T05 共用同一入口
     * @param equivFocalMm 当前主摄等效焦距
     */
    fun advise(
        face: FaceInfo?,
        subject: SubjectBox?,
        comp: CompositionHint,
        equivFocalMm: Int
    ): List<FramingAdvice> {
        // comp 当前不参与 T01/T02/T04/T06 的判定，但保留在签名里，
        // 让调用方只维护一个「画面结果 -> 机位教练」的入口。
        if (face == null || face.count <= 0) return emptyList()

        val out = ArrayList<FramingAdvice>(4)
        val wide = equivFocalMm in WIDE_FOCAL_MIN..WIDE_FOCAL_MAX

        // T02 广角透视：脸贴边会被拉宽
        if (wide && (face.centerX < EDGE_SAFE_X || face.centerX > 1f - EDGE_SAFE_X)) {
            out.add(
                FramingAdvice(
                    title = "人脸太靠边了，往中间挪一点",
                    reason = "1× 主摄是广角，画面边缘会把脸拉宽；腿放边上反而显长，脸不行",
                    severity = 2,
                    code = "T02"
                )
            )
        }

        // T01 全身照：蹲下来，脸在中心，脚在底边
        if (face.area < FACE_FULL_BODY_MAX) {
            if (face.centerY < FACE_CENTER_MIN_Y) {
                out.add(
                    FramingAdvice(
                        title = "蹲下，把手机降到腰以下",
                        reason = "站着俯拍会把腿拍短、脸拍大；蹲下来用广角，腿自然就长了",
                        severity = 2,
                        code = "T01"
                    )
                )
            } else if (subject != null && subject.bottom < FOOT_BOTTOM_MIN) {
                out.add(
                    FramingAdvice(
                        title = "退半步，把脚留在画面底部",
                        reason = "脚被裁掉会显得人悬空，腿也短了一截",
                        severity = 1,
                        code = "T01"
                    )
                )
            } else if (
                subject != null &&
                face.centerY >= FACE_CENTER_MIN_Y &&
                face.centerY <= FACE_CENTER_MAX_Y &&
                subject.bottom >= 0.90f
            ) {
                out.add(
                    FramingAdvice(
                        title = "这个机位对了，可以按快门",
                        reason = "脸在中间、脚在底边，广角把腿拉长又没让脸变形",
                        severity = 0,
                        code = "T01"
                    )
                )
            }
        }

        // T04 别切关节
        if (subject != null) {
            val bottom = subject.bottom
            if (bottom in WAIST_MIN..WAIST_MAX) {
                out.add(
                    FramingAdvice(
                        title = "取景别切在腰上",
                        reason = "切在腰线会把人截成两段，往上或往下挪半格都更顺眼",
                        severity = 1,
                        code = "T04"
                    )
                )
            } else if (bottom in KNEE_MIN..KNEE_MAX) {
                out.add(
                    FramingAdvice(
                        title = "取景别切在膝盖上",
                        reason = "膝盖是关节，切在这里会显得腿突然断掉",
                        severity = 1,
                        code = "T04"
                    )
                )
            }
        }

        // T06 朝向决定放哪条三分线
        if (abs(face.eulerYDeg) > HEAD_TURN_DEG && subject != null) {
            if (face.eulerYDeg > 0f && subject.centerX > 0.55f) {
                out.add(
                    FramingAdvice(
                        title = "他朝右看，把人放到左三分线",
                        reason = "视线方向要留出空间，否则像面壁思过",
                        severity = 1,
                        code = "T06"
                    )
                )
            } else if (face.eulerYDeg < 0f && subject.centerX < 0.45f) {
                out.add(
                    FramingAdvice(
                        title = "他朝左看，把人放到右三分线",
                        reason = "视线方向要留出空间，否则像面壁思过",
                        severity = 1,
                        code = "T06"
                    )
                )
            }
        }

        // 必须改的排前面；同级保持规则顺序（Kotlin sortedByDescending 是稳定排序）
        return out.sortedByDescending { it.severity }.take(2)
    }
}
