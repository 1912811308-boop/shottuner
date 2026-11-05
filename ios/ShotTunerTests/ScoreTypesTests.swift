// SPDX-License-Identifier: MIT
//
// ios/ShotTunerTests/ScoreTypesTests.swift —— 评分数据契约的最小单测。
//
// ★ 这个 target 存在的第一目的是**让 `xcodebuild test` 这条链路先在 CI 上跑通**，
//   而不是追求覆盖率。之前 T12 的「两端逐字段 ≤2%」验收一直卡在
//   「工程里没有 unit-test target」上，连跑都跑不起来。
//
// ★ 为什么这些用例不 `import Shared`：
//   一旦 import，测试 target 就要自己配 FRAMEWORK_SEARCH_PATHS 指向
//   Kotlin 产出的 Shared.framework，而那个 framework 是 Run Script 阶段
//   在 app target 里生成的 —— 测试 target 与它的构建顺序、架构切片都要额外对齐。
//   那是另一个独立的事，混进第一版会让「测试跑不起来」和「评分算错」两种失败
//   混在一起，排查成本翻倍。所以这里只测**纯 Swift 的数据契约**，
//   Kotlin 侧的评分正确性由 commonTest 的 277 个用例保证（那边已经有基线）。

import XCTest
@testable import ShotTuner

final class HistogramSnapshotTests: XCTestCase {

    func testEmptyIsBlank() {
        XCTAssertTrue(HistogramSnapshot.empty.isBlank, "空直方图必须被判为 blank，UI 靠它决定画不画")
    }

    func testAllZeroIsBlank() {
        let allZero = HistogramSnapshot(bins: [Float](repeating: 0, count: HistogramSnapshot.binCount))
        XCTAssertTrue(allZero.isBlank)
    }

    func testWrongLengthIsBlank() {
        let short = HistogramSnapshot(bins: [0.5, 0.6, 0.7])
        XCTAssertTrue(short.isBlank, "长度不是 64 就视为空图，避免绘图时越界")
    }

    /// 256 桶（Kotlin `FrameStats.histogram` 的原始长度）合并到 64 桶，且按最大值归一化。
    func testNormalizeFrom256Bins() {
        var raw = [Float](repeating: 0, count: 256)
        raw[128] = 1000  // 单点尖峰，归一化后峰值必须是 1

        let snapshot = HistogramSnapshot.normalized(from: raw)

        XCTAssertEqual(snapshot.bins.count, HistogramSnapshot.binCount)
        XCTAssertEqual(snapshot.bins.max() ?? 0, 1.0, accuracy: 1e-6, "最大值必须归一化到 1")
        XCTAssertFalse(snapshot.isBlank)
    }

    /// 合并规则是 **max** 而不是平均 —— 极窄的尖峰取平均会被相邻桶稀释到看不见。
    ///
    /// 构造：256 桶里只有一个桶是 4.0，其余为 0。它落在第 128*64/256 = 32 个目标桶。
    /// 若实现误用平均，那个桶会变成 4.0/4 = 1.0 之外的更小值（同一目标桶里有 4 个源桶）。
    func testMergeUsesMaxNotAverage() {
        var raw = [Float](repeating: 0, count: 256)
        raw[128] = 4.0

        let snapshot = HistogramSnapshot.normalized(from: raw)

        XCTAssertEqual(snapshot.bins[32], 1.0, accuracy: 1e-6,
                       "目标桶 32 归一化后应为 1（峰值自身归一化）；若为 0.25 说明用了平均")
        let nonZero = snapshot.bins.filter { $0 > 0 }
        XCTAssertEqual(nonZero.count, 1, "只有峰值所在的桶非零，说明尖峰没有被摊平")
    }

    /// 合并不能漏掉尾部的桶：源长度不是 64 的整数倍时，最后一个源桶必须仍被计入。
    func testTailBinIsNotDropped() {
        var raw = [Float](repeating: 0, count: 100)
        raw[99] = 1.0  // 最后一个源桶 = 峰值

        let snapshot = HistogramSnapshot.normalized(from: raw)

        XCTAssertEqual(snapshot.bins[63], 1.0, accuracy: 1e-6,
                       "最后一个源桶必须映射到目标桶 63；为 0 说明 index*i/count 的向下取整写错了")
    }

    func testEmptyInputReturnsEmpty() {
        XCTAssertEqual(HistogramSnapshot.normalized(from: []), .empty)
    }
}

final class ScoreSnapshotTests: XCTestCase {

    func testEmptyHasNoTotal() {
        XCTAssertFalse(ScoreSnapshot.empty.hasTotal, "什么都没测时不能显示总分")
        XCTAssertTrue(ScoreSnapshot.empty.anyUnmeasured)
    }

    func testEmptyDimensionsAreAllUnmeasured() {
        let dims = ScoreSnapshot.empty.dimensions
        XCTAssertEqual(dims.count, 4)
        XCTAssertTrue(dims.allSatisfy { !$0.measured })
    }

    /// 四维顺序必须固定为 水平 → 曝光 → 构图 → 清晰，UI 直接按这个顺序铺。
    func testDimensionOrderIsStable() {
        let keys = ScoreSnapshot.empty.dimensions.map { $0.key }
        XCTAssertEqual(keys, [.level, .exposure, .composition, .clarity])
    }

    /// 只要有一个维度未测，总分就不可显示 —— 这是产品口径，不是实现细节。
    func testAnyUnmeasuredBlocksTotal() {
        var snapshot = fullyMeasured()
        XCTAssertTrue(snapshot.hasTotal)

        snapshot.clarity = .unmeasured(.clarity)
        snapshot.anyUnmeasured = true

        XCTAssertFalse(snapshot.hasTotal, "清晰度未测时总分必须显示「参考分 --」")
    }

    func testDimensionMaxMatchesBoardWeights() {
        XCTAssertEqual(DimensionSnapshot.Key.level.maxScore, 25)
        XCTAssertEqual(DimensionSnapshot.Key.exposure.maxScore, 30)
        XCTAssertEqual(DimensionSnapshot.Key.composition.maxScore, 25)
        XCTAssertEqual(DimensionSnapshot.Key.clarity.maxScore, 20)
        let sum = DimensionSnapshot.Key.allCases.reduce(0) { $0 + $1.maxScore }
        XCTAssertEqual(sum, 100, "四维满分之和必须是 100，与 Android ScoreBoard 一致")
    }

    func testUnmeasuredFactoryKeepsMax() {
        let dim = DimensionSnapshot.unmeasured(.exposure)
        XCTAssertEqual(dim.score, 0)
        XCTAssertEqual(dim.max, 30)
        XCTAssertFalse(dim.measured)
        XCTAssertEqual(dim.key, .exposure)
    }

    // MARK: - 辅助

    private func fullyMeasured() -> ScoreSnapshot {
        ScoreSnapshot(
            total: 78,
            grade: "良好",
            level: DimensionSnapshot(key: .level, label: "水平", score: 25, max: 25, measured: true),
            exposure: DimensionSnapshot(key: .exposure, label: "曝光", score: 20, max: 30, measured: true),
            composition: DimensionSnapshot(key: .composition, label: "构图", score: 18, max: 25, measured: true),
            clarity: DimensionSnapshot(key: .clarity, label: "清晰（估）", score: 15, max: 20, measured: true),
            tipId: "basic_protect_highlights",
            tipText: "亮部糊了，曝光补偿降 0.3",
            tipDimension: .exposure,
            anyUnmeasured: false
        )
    }
}

final class GuideSnapshotTests: XCTestCase {

    /// 置信度低于 0.30 时不画主体标记 —— 画了就是瞎指。
    func testSubjectDrawingThreshold() {
        var guide = GuideSnapshot.empty
        XCTAssertFalse(guide.hasDrawableSubject, "空快照没有主体")

        guide.subjectConfidence = 0.30
        XCTAssertFalse(guide.hasDrawableSubject, "恰好 0.30 不算通过（判据是 > 0.30）")

        guide.subjectConfidence = 0.31
        XCTAssertTrue(guide.hasDrawableSubject)
    }

    func testEmptyIsLevel() {
        // 空快照的 isLevel 是 true，但这**不代表**可以画绿线 ——
        // 见 testEmptyHasNoReading：真正决定画不画的是 hasReading。
        XCTAssertTrue(GuideSnapshot.empty.isLevel)
        XCTAssertEqual(GuideSnapshot.empty.rollDegrees, 0)
    }

    /// 冷启动不能画参考线。
    ///
    /// 这个用例守的是一个很具体的 bug：`GuideSnapshot.empty` 的 `isLevel = true`
    /// （为了让启动瞬间不闪黄色倾斜告警），而裸 `Bool` 分不出「已端平」和「没数据」。
    /// 视图照着 `isLevel` 画，就会在产品还没分析过一帧的时候显示一条**绿色**参考线，
    /// 等于告诉用户「你已经端平了」——那是撒谎。
    ///
    /// 对应 Android `GuideOverlayView.onDraw` 开头那句 `val hint = hint ?: return`。
    func testEmptyHasNoReading() {
        XCTAssertFalse(GuideSnapshot.empty.hasReading, "冷启动不能有构图读数")
        XCTAssertFalse(GuideSnapshot.empty.pitchAvailable, "冷启动不能有俯仰读数")
    }

    /// 两个「有没有数据」的开关必须都是默认 false。
    ///
    /// 理由：契约里任何新增的非可选量，默认值都应该落在「不画」这一侧。
    /// 画错一个假的读数，比少画一个真读数严重得多 —— 前者会让用户相信错误结论。
    func testAvailabilityFlagsDefaultToFalse() {
        // 只写必填参数，让两个可选开关走默认值
        let guide = GuideSnapshot(
            rollDegrees: 0,
            isLevel: true,
            horizonDetected: false,
            horizonY: 0.5,
            tiltDeg: 0,
            subjectX: 0.5,
            subjectY: 0.5,
            subjectLeft: 0.4,
            subjectTop: 0.4,
            subjectRight: 0.6,
            subjectBottom: 0.6,
            subjectConfidence: 0,
            targetX: 0.6667,
            targetY: 0.3333,
            suggestMove: false,
            pitchDegrees: 0
        )
        XCTAssertFalse(guide.hasReading)
        XCTAssertFalse(guide.pitchAvailable)
    }

    /// 覆盖层转绿阈值是 2.5°，不是 `LevelMath` 的 1°。
    ///
    /// ★ 必须与 Kotlin `MissionThresholds.LEVEL_ENTER_DEG = 2.5f` 同值。
    ///   两端不一致会出现「线已经绿了，但任务卡判定你没做到」——
    ///   用户会觉得 App 在耍他。（Swift 侧测不到 Kotlin 的常量，
    ///   所以这条只能守 Swift 侧的值，跨端一致靠 code review。）
    func testLevelOkDegreesIsTwoPointFive() {
        XCTAssertEqual(GuideSnapshot.levelOkDegrees, 2.5, accuracy: 1e-6)
    }
}
