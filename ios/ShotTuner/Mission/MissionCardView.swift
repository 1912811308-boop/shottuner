// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/Mission/MissionCardView.swift —— 任务卡 / 成绩卡 / 达标环。
//
// ★ 这个文件只画，不判定。
//   「什么时候出卡、什么时候算达标、冷却多久」全在 Kotlin 的 MissionEngine 里
//   （理由见 MissionTypes.swift 文件头）。这里拿到的是**已经算好的 MissionSnapshot**，
//   每一帧全量重画 —— 不做任何本地状态机、不缓存上一次的快照。
//
// ★ 三个组件是分开的，不是一个巨型 View 里的 if：
//   · MissionRingView        —— 环画在取景层上（离卡片很远），必须能单独摆放
//   · MissionCardView        —— 底部悬浮卡
//   · MissionScoreCardView   —— 到位后的成绩卡，位置与任务卡接近但样式不同
//   合在一起写会逼着调用方「为了画个环先构造一张卡」，接线时必然出错。
//
// ★ 口径铁律：只说「参考分」，不说「正确/错误」「专业评测」。
//   本文件里出现的文案只有两处是我们自己加的（「到位了」「继续」），
//   其余全部来自 Kotlin 侧下发的字符串，不在 Swift 里拼中文。
//
// ★ 无障碍：按项目约定一律不加相关修饰符。这里连它们的名字都不写出来 ——
//   本项目已经有过一次「门禁用关键字扫源码，结果扫到了自己注释里那个词」
//   的先例，注释里留个词面量就是给将来的门禁埋雷。

import SwiftUI

// MARK: - 达标环

/// 取景层上的达标环：把「再挪一点点」这个模糊的体感变成一条可见的进度。
///
/// 独立成一个 View 而不是塞进卡片里，是因为它在屏幕上的位置离卡片很远
/// （环跟着主体走，卡片在底部），而且任务结束后卡片消失、环也要立刻消失。
struct MissionRingView: View {

    /// 0..1，方向已由 Kotlin `TaskPicker.progressOf` 统一（构图=距离变小、
    /// 水平=角度变小、留白=主体顶边变大）。这里**不要**再翻转任何一路。
    var progress: Float

    /// 已达标。达标后弧线发光、中心改成对勾。
    var isAchieved: Bool

    /// 直径。默认 96 是「在 6.1 吋屏上看得见、又不挡住主体」的折中值：
    /// 再大就会盖住三分交点本身，正好挡住用户要瞄准的东西。
    var size: CGFloat = 96

    /// 主描边宽度。4pt 是 3× 屏上肉眼刚好能分辨「细线」与「线」的临界值，
    /// 再细在明亮天空上会被光晕吃掉。
    private static let lineWidth: CGFloat = 4

    /// 黑色底描边的宽度 = 主描边 + 上下各 1.25pt。
    ///
    /// ★ 这是本文件最重要的一行常量：白环和浅绿环在天空 / 白墙这类亮背景上
    ///   会**完全消失** —— 不是变淡，是看不见。Android 侧用 `ringShadowPaint`
    ///   在环下面垫一层 `Color.argb(140,0,0,0)` 的粗描边解决，这里照做，
    ///   宽度取「主描边 + 2×1.25」让黑边只在环的两侧各露出 1.25pt：
    ///   露出太少在亮背景上仍然糊掉，露出太多环会显得脏。
    private static let shadowLineWidth: CGFloat = 6.5

    var body: some View {
        ZStack {
            // ① 黑底描边（整圈，不是只垫在进度弧下面）：
            //    底环与进度弧是同一条几何路径，一层黑底同时给两者提供对比度。
            //    只给进度弧垫会让底环在亮背景上消失，用户看不到"还差多少"。
            Circle()
                .stroke(
                    Palette.lineShadow,
                    style: StrokeStyle(lineWidth: Self.shadowLineWidth, lineCap: .round)
                )

            // ② 底环：25% 白，弱到不抢戏，又能标出整圈的边界。
            Circle()
                .stroke(
                    Color.white.opacity(0.25),
                    style: StrokeStyle(lineWidth: Self.lineWidth, lineCap: .round)
                )

            // ③ 进度弧。
            //
            // ★ 为什么用 trim + rotationEffect 而不是手算 Path.addArc：
            //   `addArc` 在 progress == 1 时要处理 360° 与 0° 的等价性，
            //   一旦写成 `startAngle..<(startAngle + 360°)` 就会因为弧长为整圆
            //   而被 Core Graphics 当成空路径（画不出来）；写成 359.9° 又会在
            //   12 点方向留一道缝。trim 是**按路径比例**裁的，没有这个问题。
            //
            // ★ progress == 0 时**整个弧不画**（见下面的 if）：trim 出来的空路径
            //   配上 `.round` 线帽，在部分系统版本上会留下一个孤零零的小圆点 ——
            //   那看起来像"已经有进度了"，比不画更误导。
            //   progress == 1 时 trim 恰好是整圆，两个圆角线帽在 12 点方向重合，
            //   同色重合看不出接缝，不需要额外补偿。
            if clampedProgress > 0 {
                Circle()
                    .trim(from: 0, to: clampedProgress)
                    .stroke(
                        Palette.accent,
                        style: StrokeStyle(lineWidth: Self.lineWidth, lineCap: .round)
                    )
                    // 从 12 点方向起画、顺时针长。Circle 的 trim 起点在 3 点方向，
                    // 不转这 -90° 的话进度会从右侧开始长，和"表盘"的心理模型相反。
                    .rotationEffect(.degrees(-90))
                    // 达标时给弧线一点辉光，作为「完成」的第二个信号
                    // （第一个信号是中心的对勾）。用 clear + radius 0 表示不加，
                    // 而不是写 if/else 分支 —— 分支会让两种状态的视图类型不同，
                    // 切换时 SwiftUI 会重建整棵子树，弧线会闪一下。
                    .shadow(
                        color: isAchieved ? Palette.accent.opacity(0.6) : Color.clear,
                        radius: isAchieved ? 6 : 0
                    )
            }

            // ④ 中心读数。
            if isAchieved {
                Image(systemName: "checkmark")
                    .font(.system(size: size * 0.34, weight: .bold))
                    .foregroundStyle(Palette.accent)
            } else {
                Text("\(Int(clampedProgress * 100))")
                    // 等宽数字：45 → 46 时字符宽度不变，数字不会左右跳。
                    .font(.system(size: size * 0.26, weight: .semibold, design: .monospaced))
                    .foregroundStyle(Palette.overlayText)
                    // 环心是透明的，白字同样会掉进亮背景里；
                    // 一层很淡的文字阴影就够，别加粗体描边（那会让数字糊成一团）。
                    .shadow(color: Color.black.opacity(0.55), radius: 2, y: 1)
            }
        }
        .frame(width: size, height: size)
    }

    /// 夹到 0...1，并挡掉 NaN / ±∞。
    ///
    /// ★ 为什么要防：`trim(to:)` 收到 NaN 时不会抛错，而是画出一整圈或者什么都不画，
    ///   排查时完全看不出是数据的问题。Kotlin 侧理论上只会给 0..1，
    ///   但跨语言传 Float 再多一层 DTO 转换，出现过 1.0000001 这类值。
    ///   与其在 UI 上画出 100.00001% 的怪环，不如在这里夹一次。
    private var clampedProgress: CGFloat {
        guard progress.isFinite else { return 0 }
        return CGFloat(min(max(progress, 0), 1))
    }
}

// MARK: - 任务卡

/// 底部悬浮的任务卡：一句动作 + 一句「怎么算到位」。
///
/// 调用的前提是 `snapshot.showsTaskCard == true`；但本 View 自己也会在
/// `task == nil` 时整张不画，作为第二道保险（接线漏判时不至于画出半张空卡）。
struct MissionCardView: View {

    var snapshot: MissionSnapshot

    /// 圆角 20：与覆盖层上其它悬浮件（面板、胶囊）保持同一档，
    /// 太小会显得像按钮，太大会在窄屏上把内容挤成一条。
    private static let cornerRadius: CGFloat = 20

    /// 距离屏边 16pt。再小会和圆角屏的弧线打架，再大卡片会显得飘在画面中间。
    private static let outerMargin: CGFloat = 16

    var body: some View {
        // 没有任务就什么都不画 —— 产品口径是「没任务时整卡消失」，
        // 绝不显示"暂无任务"这类占位（那会让用户觉得功能坏了）。
        if let task = snapshot.task {
            card(task)
        }
    }

    private var isAchieved: Bool { snapshot.phase == .achieved }

    private func card(_ task: MissionTaskSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            topRow(task)
            copyBlock(task)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        // ★ 材质 + 强制深色外观。
        //   `.ultraThinMaterial` 本身会自适应明暗：亮背景上它是白的，
        //   于是白色主句直接消失 —— 和环那个坑是同一个。
        //   这里不改材质类型（材质能把后面的画面糊掉，卡片才不会像一块死黑膏药），
        //   只把局部 colorScheme 钉成 dark，让材质恒定渲染成深色，
        //   白字/浅绿字在任何背景上都读得清。
        .background(
            .ultraThinMaterial,
            in: RoundedRectangle(cornerRadius: Self.cornerRadius, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: Self.cornerRadius, style: .continuous)
                .strokeBorder(
                    isAchieved ? Palette.accent : Palette.hairline,
                    lineWidth: isAchieved ? 2 : 1
                )
        }
        .environment(\.colorScheme, .dark)
        .padding(.horizontal, Self.outerMargin)
    }

    /// 上行：技法短名胶囊（左）｜倒计时（右，达标后不显示）。
    private func topRow(_ task: MissionTaskSnapshot) -> some View {
        HStack(alignment: .center, spacing: 8) {
            Text(task.skillName)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(Palette.accent)
                .lineLimit(1)
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(Palette.accent.opacity(0.18), in: Capsule())

            Spacer(minLength: 8)

            // 达标后倒计时没有意义了（锁定的任务不会再超时），
            // 留着会让用户以为"还有时间"，所以整块撤掉。
            if !isAchieved {
                countdown
            }
        }
    }

    /// 剩余秒数。≤5 秒转橙色。
    private var countdown: some View {
        // 只在最后一刻变橙：全程橙色等于没有警示。
        // 5 秒 ≈ 状态机 400ms 一 tick 的 12 个 tick，够用户做最后一次调整。
        let urgent = snapshot.remainingSeconds <= 5

        return HStack(spacing: 5) {
            Circle()
                .fill(urgent ? Palette.warn : Palette.overlayTextSecondary)
                .frame(width: 6, height: 6)

            Text("\(snapshot.remainingSeconds)s")
                .font(.caption2.weight(.semibold))
                // 等宽数字：15s → 9s 时宽度不变，右侧不会抖。
                // ★ 用 `View.monospacedDigit()`（iOS 15+）而不是 `Font.monospacedDigit()`：
                //   前者是公开文档里明确标了 iOS 15.0+ 的那个重载，后者在旧 SDK 上
                //   是否存在过版本差异，没必要拿编译门禁去赌。
                .monospacedDigit()
                .foregroundStyle(urgent ? Palette.warn : Palette.overlayTextSecondary)
                // 定宽：秒数换行成两行是最难看的破相。
                .fixedSize()
        }
    }

    /// 主句 + 副句。
    private func copyBlock(_ task: MissionTaskSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(isAchieved ? "到位了" : task.title)
                // title3（20pt）比 body 大一档：这是取景时要一眼读到的唯一一句话。
                // 再大一档（title2/22pt）在 4.7 吋屏上会把副句挤成两行。
                .font(.title3.weight(.semibold))
                .foregroundStyle(Palette.overlayText)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
                // 允许纵向伸展：中文在窄屏上折行时必须长高，
                // 否则会被裁掉下半行。
                .fixedSize(horizontal: false, vertical: true)

            Text(task.hint)
                .font(.footnote)
                .foregroundStyle(Palette.overlayTextSecondary)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

// MARK: - 成绩卡

/// 到位后的成绩卡：一个参考分 + 一句「刚才做对了什么」+ 一个继续。
///
/// 与任务卡同样是 `.ultraThinMaterial` + 强制深色，两卡切换时背景不跳。
struct MissionScoreCardView: View {

    var snapshot: MissionSnapshot

    /// 点「继续」的回调。**默认空实现**：本 View 只做视觉，
    /// 什么时候收起、收起来要不要恢复教练槽，由 ContentView 接线时决定。
    var onDismiss: () -> Void = {}

    private static let cornerRadius: CGFloat = 20
    private static let outerMargin: CGFloat = 16

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            scoreRow
            Text(snapshot.message)
                .font(.subheadline)
                .foregroundStyle(Palette.overlayText)
                .fixedSize(horizontal: false, vertical: true)
            dismissButton
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            .ultraThinMaterial,
            in: RoundedRectangle(cornerRadius: Self.cornerRadius, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: Self.cornerRadius, style: .continuous)
                .strokeBorder(Palette.hairline, lineWidth: 1)
        }
        .environment(\.colorScheme, .dark)
        .padding(.horizontal, Self.outerMargin)
    }

    /// 大号分数 + 小字「分」。
    ///
    /// 46pt 是「一眼能读、又不会把卡片撑成巨幅」的值：`largeTitle` 是 34pt，
    /// 用来当 Aha 时刻的主视觉偏小；60pt 以上在 4.7 吋屏上会让 message 掉出卡片。
    private var scoreRow: some View {
        HStack(alignment: .firstTextBaseline, spacing: 5) {
            // ★ 四维不齐时印「--」而不是数字。判据来自 `snapshot.scoreMeasured`，
            //   它是 Kotlin 在到位那一刻冻结的 `allMeasured` —— 不要在这里
            //   拿 `awardedScore > 0` 之类去猜：0 分和「未测」是两件事，
            //   非可选量表达不了「未知」，本仓已经在这上面栽过（`pitchAvailable`）。
            Text(snapshot.scoreMeasured ? "\(snapshot.awardedScore)" : "--")
                .font(.system(size: 46, weight: .bold, design: .rounded))
                // 等宽数字：82 → 100 时数字位宽固定，卡片不会左右抖。
                .monospacedDigit()
                .foregroundStyle(Palette.accent)

            // 「分」比数字小两档并与基线对齐：它是单位，不是第二个读数。
            // 未测时不显示单位 —— 「-- 分」读起来像「零分」。
            if snapshot.scoreMeasured {
                Text("分")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(Palette.overlayTextSecondary)
            }
        }
    }

    private var dismissButton: some View {
        Button(action: onDismiss) {
            Text("继续")
                .font(.subheadline.weight(.semibold))
                // ★ 浅绿底（#7FE3C0）上白字的对比度只有约 1.4:1，基本读不清；
                //   压成黑色（对比度约 12:1）才行。这句必须写在 Text 上：
                //   标签自身的前景色优先级高于 buttonStyle 给的默认色。
                .foregroundStyle(Color.black)
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .tint(Palette.accent)
    }
}
