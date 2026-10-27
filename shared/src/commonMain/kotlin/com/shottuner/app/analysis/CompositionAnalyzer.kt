package com.shottuner.app.analysis

import com.shottuner.app.model.CompositionHint
import com.shottuner.app.model.FrameStats
import com.shottuner.app.text.NumFmt
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * 构图诊断：给「主体在哪、地平线歪不歪、该往哪挪」三个问题一个可执行的答案。
 *
 * 为什么用规则 + 结构张量而不是端侧神经网络：
 * - 构图建议必须可解释，用户看到的每句话都要能对应到画面里的东西；
 * - 结构张量（梯度协方差）检测主方向只需要一次网格遍历，零延迟；
 * - 规则版在 Python 侧有等价实现（python/shottuner/composition.py），可离线回归。
 *
 * 三个硬约束：
 * 1. 主体显著性用「局部对比度 + 肤色权重」近似。它抓不住语义主体（一只猫在暗处），
 *    但足以覆盖三分法要判断的「视觉重心」，这是构图教学场景的主要诉求。
 * 2. 只接受倾角在 ±30° 内的水平结构线，避免把建筑竖线当地平线。
 * 3. 坐标系必须是「用户所见」的朝向，旋转在 FrameAnalyzer 里已经完成。
 */
object CompositionAnalyzer {

    private const val THIRD = 1f / 3f
    private const val TILT_TOLERANCE = 1.8f
    private const val MIN_COHERENCE = 0.52f

    fun analyze(s: FrameStats): CompositionHint {
        val cols = s.gridCols
        val rows = s.gridRows
        if (cols < 6 || rows < 6 || s.grid.size < cols * rows) return CompositionHint.EMPTY

        val g = s.grid
        val skin = if (s.skinGrid.size == g.size) s.skinGrid else null

        // ---- 1. 梯度场 ----
        val gx = FloatArray(g.size)
        val gy = FloatArray(g.size)
        for (y in 1 until rows - 1) {
            for (x in 1 until cols - 1) {
                val i = y * cols + x
                gx[i] = (g[i + 1] - g[i - 1]) * 0.5f
                gy[i] = (g[i + cols] - g[i - cols]) * 0.5f
            }
        }

        // ---- 2. 显著性权重：局部对比度 + 肤色 ----
        val weight = FloatArray(g.size)
        var sumW = 0.0
        var peak = 0f
        for (i in g.indices) {
            val mag = abs(gx[i]) + abs(gy[i])
            val sk = skin?.get(i) ?: 0f
            val w = mag + sk * 110f + abs(g[i] - s.meanLuma * 255f) * 0.35f
            weight[i] = w
            sumW += w
            if (w > peak) peak = w
        }
        val meanW = (sumW / g.size).toFloat()
        if (peak <= 1e-3f) return CompositionHint.EMPTY

        val confidence = ((peak - meanW) / (meanW + 6f)).coerceIn(0f, 1f)
        val thr = meanW + (peak - meanW) * 0.55f

        var cx = 0.0
        var cy = 0.0
        var mass = 0.0
        var x0 = cols
        var x1 = 0
        var y0 = rows
        var y1 = 0
        var hit = 0
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val i = y * cols + x
                if (weight[i] < thr) continue
                cx += (x + 0.5) * weight[i]
                cy += (y + 0.5) * weight[i]
                mass += weight[i]
                hit++
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
            }
        }
        if (mass <= 0.0) return CompositionHint.EMPTY

        val subjectX = (cx / mass / cols).toFloat().coerceIn(0f, 1f)
        val subjectY = (cy / mass / rows).toFloat().coerceIn(0f, 1f)
        val subjectSize = if (hit == 0) 0f else (hit.toFloat() / g.size).coerceIn(0f, 1f)

        // 主体外框：判「贴边」和「留白」比重心可靠，重心会被画面外的部分拉回中间
        val bx0 = x0.toFloat() / cols
        val bx1 = (x1 + 1).toFloat() / cols
        val by0 = y0.toFloat() / rows
        val by1 = (y1 + 1).toFloat() / rows

        // 长条形的显著性区域（地平线、栏杆、书架竖线）是结构不是主体。
        // 不排除的话，任何一张有地平线的照片都会被建议「把主体挪到交点」。
        val lineLike = (bx1 - bx0 > 0.75f && by1 - by0 < 0.22f) ||
            (by1 - by0 > 0.75f && bx1 - bx0 < 0.22f)

        // 显著性散布程度：噪声与满屏纹理会让「最亮的那些块」撒满画面，
        // 此时重心和交点建议毫无意义。先把话说成「你还没有主体」，而不是乱指挥。
        var varX = 0.0
        var varY = 0.0
        val mxc = (subjectX * cols).toDouble()
        val myc = (subjectY * rows).toDouble()
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val i = y * cols + x
                val w = weight[i]
                if (w < thr) continue
                val dx = (x + 0.5) - mxc
                val dy = (y + 0.5) - myc
                varX += w * dx * dx
                varY += w * dy * dy
            }
        }
        val spreadX = (sqrt(varX / mass) / cols).toFloat()
        val spreadY = (sqrt(varY / mass) / rows).toFloat()
        val diffuse = spreadX > 0.20f || spreadY > 0.20f

        val subjectConfidence = if (lineLike || diffuse) 0f else confidence

        // ---- 3. 结构张量：找主方向 ----
        var jxx = 0.0
        var jxy = 0.0
        var jyy = 0.0
        var hSum = 0.0
        var hMass = 0.0
        for (y in 1 until rows - 1) {
            for (x in 1 until cols - 1) {
                val i = y * cols + x
                val vx = gx[i]
                val vy = gy[i]
                val m = abs(vx) + abs(vy)
                if (m < 3f) continue
                // 只累积「更接近水平」的边缘，竖直结构线交给倾角过滤
                if (abs(vy) < abs(vx) * 0.9f) continue
                jxx += (vx * vx).toDouble()
                jxy += (vx * vy).toDouble()
                jyy += (vy * vy).toDouble()
                hSum += (y + 0.5) * m
                hMass += m
            }
        }
        val trace = jxx + jyy
        var tilt = 0f
        var horizonDetected = false
        var horizonY = 0.5f
        if (trace > 1e-3) {
            val coherence = (hypot(jxx - jyy, 2 * jxy) / trace).toFloat()
            // 弧度转角度：写死 * 180.0 / PI，不用 java.lang.Math.toDegrees
            // （commonMain 没有 Math 类），也不用 kotlin.math.toDegrees
            // —— kotlin.math 里**没有** toDegrees 这个函数，import 进来会编译失败。
            var angle = ((0.5 * atan2(2 * jxy, jxx - jyy)) * 180.0 / PI).toFloat() + 90f
            if (angle > 90f) angle -= 180f
            if (angle <= -90f) angle += 180f
            if (coherence > MIN_COHERENCE && abs(angle) <= 30f) {
                tilt = angle
                horizonDetected = true
                horizonY = (hSum / hMass / rows).toFloat().coerceIn(0f, 1f)
            }
        }

        // ---- 4. 就近三分交点 ----
        val targetX = if (subjectX < 0.5f) THIRD else 2f * THIRD
        val targetY = if (subjectY < 0.5f) THIRD else 2f * THIRD
        val dist = hypot(subjectX - targetX, subjectY - targetY)

        // ---- 5. 建议 ----
        val tips = ArrayList<String>(4)
        var score = 100
        // 倾斜单独记一笔：四维评分里「水平度」已经罚过一次，构图不能再罚一次
        var tiltPenalty = 0

        val nearEdge = bx0 < 0.05f || bx1 > 0.95f || by0 < 0.05f || by1 > 0.95f
        if (subjectConfidence > 0.30f && nearEdge) {
            tips.add("主体贴到画面边缘了，退半步或把镜头往主体方向挪一点")
            score -= 15
        }

        if (horizonDetected && abs(tilt) > TILT_TOLERANCE) {
            val dir = if (tilt > 0f) "顺时针" else "逆时针"
            tips.add(
                "画面有 ${NumFmt.fixed(abs(tilt), 0)}° 的倾斜，${dir}转一点手机，" +
                    "让橙色虚线和绿色横线重合"
            )
            tiltPenalty = min(20f, 4f * abs(tilt)).roundToInt()
            score -= tiltPenalty
        }

        if (horizonDetected && horizonY > 0.44f && horizonY < 0.56f) {
            tips.add("地平线横在正中间会显得呆板，压低取景让地平线落在下三分之一，或抬高让它落在上三分之一")
            score -= 12
        }

        if (subjectConfidence > 0.30f) {
            if (dist > 0.10f) {
                val where = when {
                    targetX < 0.5f && targetY < 0.5f -> "左上"
                    targetX < 0.5f -> "左下"
                    targetY < 0.5f -> "右上"
                    else -> "右下"
                }
                tips.add("把主体放到$where 那个交点上，现在偏了 ${(dist * 100).roundToInt()}% 画面")
                score -= min(18f, 110f * dist).roundToInt()
            }
            if (s.skinRatio > 0.06f && by0 < 0.08f) {
                tips.add("头顶快顶到画框了，上方多留一点空间会舒服很多")
                score -= 8
            }
            if (subjectSize > 0.60f) {
                tips.add("主体把画面塞满了，退一步留点环境，画面才有呼吸感")
                score -= 8
            }
        } else if (s.edgeDensity < 0.10f) {
            tips.add("画面比较空，没有明显主体，走近一点或等一个人、一只鸟进入画面")
            score -= 10
        } else {
            tips.add("画面元素比较散，先选一个主体（人、物、建筑），再把它放到交点上")
            score -= 8
        }

        if (tips.isEmpty()) {
            tips.add(if (horizonDetected) "构图稳，地平线水平，可以按快门" else "构图没问题，可以按快门了")
        }

        return CompositionHint(
            score = score.coerceIn(0, 100),
            tiltDeg = tilt,
            horizonDetected = horizonDetected,
            horizonY = horizonY,
            subjectX = subjectX,
            subjectY = subjectY,
            subjectConfidence = subjectConfidence,
            subjectSize = subjectSize,
            subjectLeft = bx0,
            subjectTop = by0,
            subjectRight = bx1,
            subjectBottom = by1,
            targetX = targetX,
            targetY = targetY,
            suggestMove = subjectConfidence > 0.30f && dist > 0.10f,
            tips = tips.take(2),
            // 把倾斜那一笔加回来，得到"只反映构图、不含倾斜"的分支分数
            scoreNoTilt = (score + tiltPenalty).coerceIn(0, 100)
        )
    }
}
