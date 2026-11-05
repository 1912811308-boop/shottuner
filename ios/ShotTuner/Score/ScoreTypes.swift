// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/Score/ScoreTypes.swift —— 评分与覆盖层的**唯一数据类型契约**。
//
// ★ 这个文件只放数据，不放任何逻辑。
//   它的作用是让三个互不相干的开发面能并行：
//
//     ① Kotlin 门面（commonMain + iosMain）   → 产出 IosScoreDto
//     ② ScoreCoordinator（本目录）            → 把 IosScoreDto 翻成这里的 Swift 类型
//     ③ GuideOverlayView / HistogramView      → 只读这里的类型画图
//
//   ②③ 之间只通过这些 struct 通信，谁都不需要知道 Kotlin 侧长什么样。
//
// ★ 为什么不在 Swift 里直接构造 Kotlin 的 FrameStats / CompositionHint：
//   那两个类带 FloatArray 和 List<String> 字段，跨语言构造要装箱，而且
//   ScoreBoard.score() 的可见面会随着重构漂移。所以 Kotlin 侧收一个**全标量**的
//   扁平入参，算完吐一个**全标量**的扁平 DTO —— 见 IosBridge.computeScore。
//
// ★ 产品口径（改这些字之前先想清楚）：
//   · 叫「参考分」，不叫"得分"/"评分"/"准确率"
//   · **任一维度未测时，总分显示「参考分 --」而不是数字** —— 别拿 0 假装有数据
//   · 提示卡只说**一条**、只给动作，原理不进卡片

import Foundation

// MARK: - 单个维度

/// 四维之一。`level` / `exposure` / `composition` / `clarity`。
///
/// 顺序与 `ScoreBoard.score()` 返回的顺序一致（LEVEL → EXPOSURE → COMPOSITION → CLARITY），
/// UI 可以按这个顺序直接铺。
struct DimensionSnapshot: Equatable, Identifiable {

    /// 维度的稳定标识。Kotlin 侧用同名小写字符串传递，两端靠它对齐。
    enum Key: String, CaseIterable {
        case level
        case exposure
        case composition
        case clarity

        /// 中文标题，与 Kotlin `DimensionScore.label` 同源。
        var displayName: String {
            switch self {
            case .level: return "水平"
            case .exposure: return "曝光"
            case .composition: return "构图"
            case .clarity: return "清晰"
            }
        }

        /// 该维度的满分。总和 = 100，与 Android 一致。
        var maxScore: Int {
            switch self {
            case .level: return 25
            case .exposure: return 30
            case .composition: return 25
            case .clarity: return 20
            }
        }
    }

    var key: Key
    /// Kotlin 侧给的文案（清晰维度是「清晰（估）」，比 `key.displayName` 更准）。
    ///
    /// ★ 一律优先用这个字段显示，`displayName` 只作兜底 —— 因为 Kotlin 侧
    ///   会把「清晰（估）」这类带修饰的写法一起下发，硬编码中文会在两端分叉。
    var label: String
    var score: Int
    var max: Int
    /// `false` = 本帧没有数据（例如清晰度每 15 帧才算一次）。
    ///
    /// ★ 未测时 `score` 恒为 0，**不得**把它当真实分数显示或参与总分计算。
    var measured: Bool

    var id: Key { key }

    /// 一个「未测」占位维度。
    static func unmeasured(_ key: Key) -> DimensionSnapshot {
        DimensionSnapshot(
            key: key,
            label: key.displayName,
            score: 0,
            max: key.maxScore,
            measured: false
        )
    }
}

// MARK: - 整体评分

/// 一次完整评分的结果。对应 Kotlin `ScoreResult` 的扁平化版本。
struct ScoreSnapshot: Equatable {

    /// 四维之和（0..100）。**仅在 `anyUnmeasured == false` 时有意义。**
    var total: Int

    /// 等级文案：「优秀」/「良好」/「及格」/「待改进」。
    var grade: String

    var level: DimensionSnapshot
    var exposure: DimensionSnapshot
    var composition: DimensionSnapshot
    var clarity: DimensionSnapshot

    /// 建议对应的技法 id（可空串）。用来做「去看这条技法」的跳转。
    var tipId: String

    /// 提示卡唯一一条建议。空串表示"这张没什么可挑的"，此时卡片显示正面文案。
    var tipText: String

    /// 这条建议属于哪个维度，决定卡片的强调色。
    var tipDimension: DimensionSnapshot.Key?

    /// 是否**任一**维度未测。
    ///
    /// ★ 铁律：为 `true` 时总分位必须显示「参考分 --」，不能显示数字。
    ///   这是为了不拿半个数据骗人 —— 用户会拿这个数字去判断照片好坏。
    var anyUnmeasured: Bool

    /// 四维按固定顺序返回，方便 `ForEach` 铺。
    var dimensions: [DimensionSnapshot] {
        [level, exposure, composition, clarity]
    }

    /// 尚未测过任何一帧。
    static let empty = ScoreSnapshot(
        total: 0,
        grade: "",
        level: .unmeasured(.level),
        exposure: .unmeasured(.exposure),
        composition: .unmeasured(.composition),
        clarity: .unmeasured(.clarity),
        tipId: "",
        tipText: "",
        tipDimension: nil,
        anyUnmeasured: true
    )

    /// 总分是否可显示（四维全测过）。
    var hasTotal: Bool { !anyUnmeasured }
}

// MARK: - 直方图

/// 亮度直方图。**只用于显示，不参与评分。**
///
/// ★ 桶数固定 [`HistogramSnapshot.binCount`]。Kotlin `FrameStats.histogram` 是 256 桶，
///   由 `ScoreCoordinator` 侧合并成 64 桶再进来：屏幕上最多也就几百像素宽，
///   256 桶里必然有相邻桶落在同一列，提前合并还能少一次遍历。
struct HistogramSnapshot: Equatable {

    /// 固定 64 桶。
    static let binCount = 64

    /// 64 个 0..1 的归一化值（已除以最大值，画图直接乘高度）。
    ///
    /// 长度恒为 `binCount`，越界视为空图。
    var bins: [Float]

    /// 是否为空白直方图（全 0 / 长度不对）。
    var isBlank: Bool {
        bins.count != Self.binCount || !bins.contains { $0 > 0 }
    }

    static let empty = HistogramSnapshot(bins: [Float](repeating: 0, count: HistogramSnapshot.binCount))

    /// 把任意长度的直方图合并到 64 桶，并按最大值归一化。
    ///
    /// ★ 合并用 **max** 而不是平均：极窄的尖峰（干净的夜空、单点高光）
    ///   取平均会被相邻桶稀释到看不见。Android 侧 `HistogramView` 的注释
    ///   也是这么写的（"每根像素列取该区间内的最大值"）。
    static func normalized(from raw: [Float]) -> HistogramSnapshot {
        guard !raw.isEmpty else { return .empty }

        var merged = [Float](repeating: 0, count: binCount)
        for (index, value) in raw.enumerated() {
            // index * binCount / raw.count 而不是 index / (raw.count / binCount)：
            // 后者在 raw.count 不是 binCount 整数倍时会漏掉尾部的桶。
            let target = index * binCount / raw.count
            if target >= 0 && target < binCount && value > merged[target] {
                merged[target] = value
            }
        }

        let peak = merged.max() ?? 0
        guard peak > 0 else { return HistogramSnapshot(bins: merged) }
        for i in 0..<binCount { merged[i] /= peak }
        return HistogramSnapshot(bins: merged)
    }
}

// MARK: - 覆盖层几何

/// 取景覆盖层要画的所有几何量。全部归一化到 0..1（角度除外）。
///
/// 对应 Android `GuideOverlayView` 收到的 `CompositionHint` + `roll`。
struct GuideSnapshot: Equatable {

    /// 覆盖层的「端平了没」阈值（度）。
    ///
    /// ★ **2.5°，不是 1°。** 这是两个不同的口径，别混：
    ///   · `LevelMath.LEVEL_TOLERANCE_DEG = 1°` 是 `LevelMath.isLevel(roll)` 的口径；
    ///   · 覆盖层参考线转绿用的是 `GuideOverlayView.LEVEL_OK_DEG = 2.5°`，
    ///     它**必须与 `MissionThresholds.LEVEL_ENTER_DEG = 2.5°` 同值** ——
    ///     否则会出现「线已经绿了，但任务卡判定你没做到」这种自相矛盾的画面。
    ///     Android 侧那句注释写得很清楚：「判定用的圈就是画出来的圈」。
    ///
    /// ★ 这个值暂时硬编码在 Swift 侧；T13 收尾时应由 Kotlin 导出
    ///   `levelOkDegrees()` 让它真正单一来源，现在写在这里是为了先有唯一出处。
    static let levelOkDegrees: Float = 2.5

    /// 是否已经收到可信的**构图**读数。
    ///
    /// 对应 Android `GuideOverlayView.onDraw` 开头的 `val hint = hint ?: return`，
    /// 也就是「`CompositionHint` 到手了没」。由 `ScoreCoordinator.land(...)`
    /// 在从 DTO 填完构图字段后置 `true`。
    ///
    /// ★ **姿态读数（`rollDegrees` / `pitchDegrees`）不置这个标志。**
    ///   姿态先到、构图还没算出来时，覆盖层仍然不该画参考线 —— 因为主体框、
    ///   地平线、三分交点全都来自构图读数，半份数据画出来是错位的。
    ///   姿态只影响 `isLevel`（而且 `resolveIsLevel` 里还有 `guard hasReading`，
    ///   没构图读数时直接给 `false`，不可能出现"姿态一到位就变绿"）。
    ///
    /// ★ **为什么必须有这个字段**：`isLevel` 是裸 `Bool`，分不出「已端平」和
    ///   「还没收到数据」。而 `GuideSnapshot.empty` 里 `isLevel = true`
    ///   （为了让刚启动时不闪黄色告警），于是视图会照规则画一条**绿色**参考线 ——
    ///   等于在产品冷启动、一帧都没分析过的时候告诉用户「你已经端平了」。
    ///   这跟 `pitchAvailable` 是同一类缺陷：**非可选量表达不了「未知」**。
    ///
    /// ★ 为 `false` 时视图**不画**：水平参考线、地平线倾斜线、主体标记、目标标记。
    ///   网格（三分线 / 中心十字）和俯仰刻度**照画** —— 它们不依赖构图读数，
    ///   这也是 Android 的行为（`hint ?: return` 发生在画完网格和刻度之后）。
    var hasReading: Bool = false

    /// 手机的横滚角（度），**正值表示画面右侧偏低**。
    var rollDegrees: Float

    /// 是否已经端平。判据见 [`GuideSnapshot.levelOkDegrees`]（2.5°）。
    ///
    /// 双数据源：`horizonDetected` 为真时看 `tiltDeg`（画面里的结构线真歪没歪，
    /// 最准），否则看 `rollDegrees`（手机歪不等于画面歪）。
    /// ★ 这个选择由 `ScoreCoordinator` 做，**不是**由视图做。
    var isLevel: Bool

    /// 是否检测到可信的地平线 / 水平结构线。
    ///
    /// ★ 为 `false` 时，「端平了没」只能靠重力传感器（`rollDegrees`）判断，
    ///   不能拿 `tiltDeg`（它是画面内结构线的角度，此刻没有意义）。
    var horizonDetected: Bool

    /// 地平线的纵向位置（0..1）。
    var horizonY: Float

    /// 地平线倾角（度），正值表示右侧偏低。
    var tiltDeg: Float

    /// 主体重心（0..1）。
    var subjectX: Float
    var subjectY: Float

    /// 主体外框（0..1）。
    var subjectLeft: Float
    var subjectTop: Float
    var subjectRight: Float
    var subjectBottom: Float

    /// 主体显著性置信度（0..1）。低于 0.30 时不画主体标记 —— 画了就是瞎指。
    var subjectConfidence: Float

    /// 三分交点建议落点（0..1）。
    var targetX: Float
    var targetY: Float

    /// 是否有"该把主体挪到交点"的建议。
    /// ★ 是 `Bool`，不是 `Boolean`：`Boolean` 在 Swift 里是 CoreFoundation 的
    ///   `UInt8` 别名，写上它既编译不过（下面 `empty` 给的是 `false`），
    ///   也会让 `if guide.suggestMove` 变成「整数当布尔用」的坑。
    var suggestMove: Bool

    /// 俯仰角（度），用于俯仰刻度条。
    ///
    /// ★ 只在 `pitchAvailable == true` 时有意义。裸的 `Float` 分不出
    ///   「恰好水平（0°）」和「还没收到姿态数据（0）」—— 前者要画刻度，
    ///   后者不能画（画一条 0° 的刻度等于告诉用户"你已经平了"，那是撒谎）。
    var pitchDegrees: Float

    /// 是否有可用的俯仰角读数。
    ///
    /// 对应 Android `GuideOverlayView` 里 `pitchDeg != null` 那个判断。
    /// ★ 默认 `false`：宁可先不画，也不要画一条假的 0° 刻度。
    var pitchAvailable: Bool = false

    /// 主体标记是否可以画。
    var hasDrawableSubject: Bool { subjectConfidence > 0.30 }

    static let empty = GuideSnapshot(
        // 冷启动没有任何读数 —— 视图据此不画参考线 / 地平线 / 主体
        // （否则 isLevel 的默认 true 会让画面一上来就是绿的，等于撒谎）
        hasReading: false,
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
        pitchDegrees: 0,
        // 空快照没有姿态数据 —— 不画俯仰刻度（见 pitchAvailable 的注释）
        pitchAvailable: false
    )
}
