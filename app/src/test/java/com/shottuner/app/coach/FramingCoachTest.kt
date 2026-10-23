package com.shottuner.app.coach

import com.shottuner.app.model.CompositionHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 机位教练规则单测（纯 JVM，不依赖 Robolectric）。
 *
 * 用例口径与 python/tests/test_framing.py 保持一致：
 * 断言「用户能不能照着做」，即提示里是否出现该做的动作。
 */
class FramingCoachTest {

    private fun face(
        cx: Float = 0.5f,
        cy: Float = 0.5f,
        area: Float = 0.03f,
        bottom: Float = 0.12f,
        euler: Float = 0f
    ) = FaceInfo(
        count = 1,
        centerX = cx,
        centerY = cy,
        area = area,
        boxBottom = bottom,
        eulerYDeg = euler
    )

    private fun subject(
        bottom: Float = 0.95f,
        left: Float = 0.3f,
        right: Float = 0.7f,
        top: Float = 0.05f
    ) = SubjectBox(left = left, top = top, right = right, bottom = bottom)

    private fun titles(list: List<FramingAdvice>): String =
        list.joinToString(" / ") { it.title }

    @Test
    fun lowFaceInFullBodyAsksToSquat() {
        val out = FramingCoach.advise(
            face(cy = 0.25f, area = 0.03f),
            subject(bottom = 0.95f),
            CompositionHint.EMPTY,
            24
        )
        assertTrue("全身照脸偏上时必须给建议", out.isNotEmpty())
        assertTrue("应提示蹲下，实际：${titles(out)}", titles(out).contains("蹲下"))
        assertEquals("T01", out.first().code)
    }

    @Test
    fun feetCutAsksToStepBack() {
        val out = FramingCoach.advise(
            face(cy = 0.5f, area = 0.03f),
            subject(bottom = 0.80f),
            CompositionHint.EMPTY,
            24
        )
        assertTrue("脚被裁掉时应提示退半步，实际：${titles(out)}", titles(out).contains("退半步"))
    }

    @Test
    fun centeredFaceAndFeetConfirmsFraming() {
        val out = FramingCoach.advise(
            face(cy = 0.50f, area = 0.03f),
            subject(bottom = 0.95f),
            CompositionHint.EMPTY,
            24
        )
        assertTrue("脸在中心、脚在底边应确认，实际：${titles(out)}", titles(out).contains("对了"))
        assertEquals(0, out.first().severity)
    }

    @Test
    fun faceNearEdgeWarnsOnWideLens() {
        val out = FramingCoach.advise(
            face(cx = 0.10f, area = 0.10f),
            subject(),
            CompositionHint.EMPTY,
            24
        )
        assertTrue("广角 + 脸贴边应提示往中间挪，实际：${titles(out)}", titles(out).contains("靠边"))
        assertEquals(2, out.first().severity)
    }

    @Test
    fun faceAtCenterDoesNotWarnAboutEdge() {
        val out = FramingCoach.advise(
            face(cx = 0.50f, area = 0.10f),
            subject(),
            CompositionHint.EMPTY,
            24
        )
        assertFalse("脸在中间不该报贴边，实际：${titles(out)}", titles(out).contains("靠边"))
    }

    @Test
    fun edgeBoundaryIsStrict() {
        val inside = FramingCoach.advise(
            face(cx = 0.18f, area = 0.10f),
            subject(),
            CompositionHint.EMPTY,
            24
        )
        val outside = FramingCoach.advise(
            face(cx = 0.17f, area = 0.10f),
            subject(),
            CompositionHint.EMPTY,
            24
        )
        assertFalse("0.18 恰好是安全边界，不该触发", titles(inside).contains("靠边"))
        assertTrue("0.17 已越过安全边界，应触发", titles(outside).contains("靠边"))
    }

    @Test
    fun edgeIsFineOnTeleLens() {
        val out = FramingCoach.advise(
            face(cx = 0.10f, area = 0.10f),
            subject(),
            CompositionHint.EMPTY,
            50
        )
        assertFalse("长焦不该报广角透视变形，实际：${titles(out)}", titles(out).contains("靠边"))
    }

    @Test
    fun waistCutIsFlagged() {
        val out = FramingCoach.advise(
            face(area = 0.10f),
            subject(bottom = 0.48f),
            CompositionHint.EMPTY,
            50
        )
        assertTrue("底边 0.48 应提示切腰，实际：${titles(out)}", titles(out).contains("腰"))
    }

    @Test
    fun kneeCutIsFlagged() {
        val out = FramingCoach.advise(
            face(area = 0.10f),
            subject(bottom = 0.67f),
            CompositionHint.EMPTY,
            50
        )
        assertTrue("底边 0.67 应提示切膝，实际：${titles(out)}", titles(out).contains("膝盖"))
    }

    @Test
    fun betweenWaistAndKneeIsNotFlagged() {
        val out = FramingCoach.advise(
            face(area = 0.10f),
            subject(bottom = 0.55f),
            CompositionHint.EMPTY,
            50
        )
        assertFalse(
            "底边 0.55 不在关节禁区，实际：${titles(out)}",
            titles(out).contains("膝盖") || titles(out).contains("腰上")
        )
    }

    @Test
    fun facingRightOnRightSideAsksForLeftThird() {
        val out = FramingCoach.advise(
            face(cx = 0.70f, area = 0.10f, euler = 25f),
            subject(bottom = 0.85f, left = 0.55f, right = 0.90f),
            CompositionHint.EMPTY,
            50
        )
        assertTrue("朝右且人在右侧应建议左三分线，实际：${titles(out)}", titles(out).contains("左三分线"))
    }

    @Test
    fun facingCameraDoesNotTriggerThirdLineAdvice() {
        val out = FramingCoach.advise(
            face(cx = 0.70f, area = 0.10f, euler = 0f),
            subject(bottom = 0.85f, left = 0.55f, right = 0.90f),
            CompositionHint.EMPTY,
            50
        )
        assertFalse("正对镜头不该建议三分线，实际：${titles(out)}", titles(out).contains("三分线"))
    }

    @Test
    fun noFaceReturnsNothing() {
        assertTrue(
            FramingCoach.advise(null, subject(), CompositionHint.EMPTY, 24).isEmpty()
        )
        assertTrue(
            FramingCoach.advise(
                FaceInfo(0, 0f, 0f, 0f, 0f),
                subject(),
                CompositionHint.EMPTY,
                24
            ).isEmpty()
        )
    }

    @Test
    fun returnsAtMostTwoAdvices() {
        val out = FramingCoach.advise(
            face(cx = 0.10f, cy = 0.30f, area = 0.10f),
            subject(bottom = 0.48f),
            CompositionHint.EMPTY,
            24
        )
        assertTrue("最多两条，实际 ${out.size}", out.size <= 2)
    }
}
