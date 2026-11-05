// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/Mission/MissionCardPreviews.swift —— 任务卡的 Xcode 预览集。
//
// ★ 为什么预览单独一个文件：
//   Xcode 的预览面板会把**当前文件里**的预览按顺序列出来。任务卡的六种呈现
//   （三档环 + 三种任务 + 达标 + 成绩）如果和 300 行实现挤在一个文件里，
//   每次调样式都得滚过实现体才能点到预览。分开之后这个文件就是一张"状态表"，
//   打开就能一眼扫完所有分支。
//
// ★ 为什么预览不用默认白底，而是自己铺一层"天空"渐变：
//   任务卡与环是**叠在实时取景画面上**的。白底预览永远看不出「白字 / 白环在亮
//   背景上消失」这个本文件最大的坑 —— 而它恰恰是最容易在真机上翻车的地方。
//   所以下面的舞台统一用浅色天空渐变（最坏情况），能在这里看清就够了。
//
// ★ 为什么用 `#Preview`（宏）而不是 `PreviewProvider`：
//   本项目锁 Xcode 15（`ios/project.yml` 的 `projectFormat: xcode15_0`）。
//   `#Preview` 在 Xcode 15 已支持部署目标低于 iOS 17 的工程
//   （Xcode 15 Release Notes：*"The #Preview can now be used in projects with
//   deployment targets prior to iOS 17"*），所以 iOS 16.0 的部署目标没问题。
//   ★ 唯一的差别：不写 `@available(iOS 16.0, *)` 时，预览跑在 iOS 17 模拟器上；
//     本机没有 iOS 17 模拟器时给对应 `#Preview` 加上那行可用性标注即可。
//   ★ 如果哪天把工程降回 Xcode 14，这一整个文件都要改成 `PreviewProvider`。
//
// ★ 预览里的文案是**真文案**（`TaskPicker` 的 TITLES / HINTS / SKILL_NAMES），
//   不是随手编的"标题一/标题二"。预览的意义之一就是看真文案会不会超长折行。

import SwiftUI

// MARK: - 采样数据

/// 预览用的快照工厂。
///
/// ★ 刻意 `private` 且只放在这个文件里：它是**预览素材**，不是产品数据源。
///   如果把它挪进 MissionTypes.swift，就会有人拿它当"默认值"用，届时
///   上线包里出现一张写死的"把手机端平"卡片，而且没人会想到去查预览文件。
private enum MissionSamples {

    // ── 三类任务（与 Kotlin `TaskPicker` 的白名单一一对应）─────────────────

    static let level = MissionTaskSnapshot(
        tipId: "landscape_horizon_level",
        kind: .level,
        title: "把手机端平",
        hint: "转一下，橙线压到绿线上",
        skillName: "地平线端平"
    )

    /// `title` 里的 `{where}` 已由 Kotlin 侧替换成方位词，这里写替换后的成品。
    static let composition = MissionTaskSnapshot(
        tipId: "portrait_eyes_thirds",
        kind: .composition,
        title: "把主体挪到左上交点",
        hint: "让白点进圈，圈变绿就到位",
        skillName: "三分法"
    )

    static let headroom = MissionTaskSnapshot(
        tipId: "portrait_headroom",
        kind: .headroom,
        title: "头顶多留一点空间",
        hint: "镜头往下压一指，别顶到画框",
        skillName: "头顶留白"
    )

    // ── 三个相位 ──────────────────────────────────────────────────────────

    /// 判定中。`ringProgress` 是 Kotlin 算好的 0..1，方向已统一。
    static func inProgress(
        _ task: MissionTaskSnapshot,
        progress: Float = 0.45,
        seconds: Int = 12
    ) -> MissionSnapshot {
        MissionSnapshot(
            phase: .inProgress,
            task: task,
            ringProgress: progress,
            remainingSeconds: seconds,
            awardedScore: 0,
            message: ""
        )
    }

    /// 刚派发的 800ms 缓冲期：环还没开始长，倒计时满格。
    static func shown(_ task: MissionTaskSnapshot) -> MissionSnapshot {
        MissionSnapshot(
            phase: .shown,
            task: task,
            ringProgress: 0,
            remainingSeconds: 15,
            awardedScore: 0,
            message: ""
        )
    }

    /// 已达标并锁定。
    static func achieved(_ task: MissionTaskSnapshot) -> MissionSnapshot {
        MissionSnapshot(
            phase: .achieved,
            task: task,
            ringProgress: 1,
            remainingSeconds: 0,
            awardedScore: 0,
            message: ""
        )
    }

    /// 成绩卡。`message` 的句式与 Android `showAchieveCard()` 一致。
    static func scored(score: Int, skillName: String) -> MissionSnapshot {
        MissionSnapshot(
            phase: .scored,
            task: level,
            ringProgress: 0,
            remainingSeconds: 0,
            awardedScore: score,
            message: "你刚才做对了：\(skillName)"
        )
    }
}

// MARK: - 预览舞台

/// 把预览内容放在"浅色天空"上。
///
/// ★ 浅到接近白：这是任务卡能遇到的最坏背景（正对天空 / 白墙）。
///   浅色渐变上都读得清，真机上就不会有更差的情况。
private struct PreviewStage<Content: View>: View {

    private let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [Color(argb: 0xFFA9CBDF), Color(argb: 0xFFF2F7FA)],
                startPoint: .top,
                endPoint: .bottom
            )
            content
        }
        .ignoresSafeArea()
    }
}

// MARK: - 环

/// 三档进度并排：0（刚派发）/ 0.45（进行中）/ 1（达标）。
///
/// ★ 特意把 0 和 1 这两个边界放进预览：`trim` 在两端的行为（0 会不会留下
///   一个小圆点、1 会不会在 12 点方向留缝）只能靠眼睛看，不能靠推理。
#Preview("环 · 0 / 45% / 达标") {
    PreviewStage {
        HStack(spacing: 20) {
            VStack(spacing: 8) {
                MissionRingView(progress: 0, isAchieved: false)
                Text("0").font(.caption2).foregroundStyle(.white)
            }
            VStack(spacing: 8) {
                MissionRingView(progress: 0.45, isAchieved: false)
                Text("0.45").font(.caption2).foregroundStyle(.white)
            }
            VStack(spacing: 8) {
                MissionRingView(progress: 1, isAchieved: true)
                Text("1（达标）").font(.caption2).foregroundStyle(.white)
            }
        }
    }
}

// MARK: - 任务卡

#Preview("任务卡 · 进行中（45% · 12s）") {
    PreviewStage {
        VStack {
            Spacer()
            MissionCardView(snapshot: MissionSamples.inProgress(MissionSamples.level))
                .padding(.bottom, 24)
        }
    }
}

/// 刚派发、环还是空的。这一档要确认「环空着但卡片有内容」不会显得像坏了。
#Preview("任务卡 · 刚派发（0% · 15s）") {
    PreviewStage {
        VStack {
            Spacer()
            MissionCardView(snapshot: MissionSamples.shown(MissionSamples.composition))
                .padding(.bottom, 24)
        }
    }
}

/// 剩余 ≤5 秒：倒计时转橙。这是唯一一处用橙色表达"来不及"的地方。
#Preview("任务卡 · 最后 4 秒（橙色告警）") {
    PreviewStage {
        VStack {
            Spacer()
            MissionCardView(
                snapshot: MissionSamples.inProgress(MissionSamples.headroom, progress: 0.7, seconds: 4)
            )
            .padding(.bottom, 24)
        }
    }
}

/// 达标：边框亮绿 2pt、主句「到位了」、倒计时消失。
#Preview("任务卡 · 已达标") {
    PreviewStage {
        VStack {
            Spacer()
            MissionCardView(snapshot: MissionSamples.achieved(MissionSamples.level))
                .padding(.bottom, 24)
        }
    }
}

/// 三种 `MissionKind` 各一张 —— 主句长度差别最大的是「把主体挪到左上交点」（9 字），
/// 它决定卡片在 4.7 吋屏上会不会折成两行。
#Preview("任务卡 · 三种任务（level / composition / headroom）") {
    PreviewStage {
        VStack(spacing: 12) {
            MissionCardView(snapshot: MissionSamples.inProgress(MissionSamples.level, progress: 0.3))
            MissionCardView(snapshot: MissionSamples.inProgress(MissionSamples.composition, progress: 0.6))
            MissionCardView(snapshot: MissionSamples.inProgress(MissionSamples.headroom, progress: 0.9))
        }
        .padding(.vertical, 16)
    }
}

// MARK: - 成绩卡

#Preview("成绩卡 · 86 分") {
    PreviewStage {
        VStack {
            Spacer()
            MissionScoreCardView(snapshot: MissionSamples.scored(score: 86, skillName: "三分法"))
                .padding(.bottom, 24)
        }
    }
}

/// 满分与低分两个边界：数字位数变化（2 位 → 3 位）时卡片不能跳宽。
#Preview("成绩卡 · 100 分 / 62 分") {
    PreviewStage {
        VStack(spacing: 12) {
            MissionScoreCardView(snapshot: MissionSamples.scored(score: 100, skillName: "地平线端平"))
            MissionScoreCardView(snapshot: MissionSamples.scored(score: 62, skillName: "头顶留白"))
        }
        .padding(.vertical, 16)
    }
}

/// 组合态：环 + 任务卡同屏。真机上这两者是叠在一起的，
/// 分开预览容易忽略「环挡住了卡片上方的画面」这件事。
#Preview("组合 · 环 + 任务卡") {
    PreviewStage {
        VStack(spacing: 0) {
            Spacer()
            MissionRingView(progress: 0.45, isAchieved: false)
            Spacer()
            MissionCardView(snapshot: MissionSamples.inProgress(MissionSamples.composition))
                .padding(.bottom, 24)
        }
    }
}
