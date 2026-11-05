// SPDX-License-Identifier: MIT
//
// shared/src/iosMain —— 任务卡的跨语言输出形状（iOS 专属，不进 commonMain）。
//
// ★ 为什么放 iosMain 而不是 commonMain：
//   Android 侧压根不需要这个 DTO —— 它直接读 MissionEngine 的 `state` / `current`
//   并自己 setText，多一层 DTO 只是多一处可能写错的地方。这个类存在的唯一理由是
//   「Swift 无法消费 Kotlin 的类对象 / List / Float?」，属于平台适配层。
//   放进 commonMain 会让 Android 也"拥有"一个它永远不会用的形状，
//   日后还会有人误以为它是两端共用的契约。
//
// ★ 为什么字段全是标量 / 可空类，没有一个数组：
//   本仓库已定的跨语言铁律 —— **跨语言边界只传标量**。`List` 会桥接成 NSArray
//   （装箱 + 拷贝），`Float?` 会变成 `KotlinFloat?`（Swift 侧每次都要拆包）。
//   组装 MissionSample 需要 FrameStats / CompositionHint / List<Recommendation>，
//   这三样**只在 Kotlin 侧流动**（见 IosMissionBridge），Swift 只送 now / roll / rollValid。
//
// ★ 文案口径：本文件**不产出任何自己拼的中文**，全部是搬运（`MissionTask.title` /
//   `MissionTask.hint` / `MissionTask.skillName` 与成绩卡那一句），
//   保证 iOS 与 Android 用的是同一份文案，不会各改各的。

package com.shottuner.app.ios

/**
 * 一次 tick 的任务快照：Swift 侧 `MissionModel` 把它逐字段翻成 `MissionSnapshot`。
 *
 * ## 字段映射口径（逐字段，Swift 侧照这张表写，不要自己推断）
 *
 * | 本 DTO 字段 | Swift 落点 | 口径 |
 * |---|---|---|
 * | [phase] | 诊断 / 日志 | `MissionState.name`，**大写蛇形**（`IDLE` / `TASK_SHOWN` / …） |
 * | [phaseRawValue] | `MissionSnapshot.phase` | **直接** `MissionPhase(rawValue:)`，值为 `idle` / `shown` / `inProgress` / `achieved` / `scored` |
 * | [title] | `MissionTaskSnapshot.title` | `current?.title`，无任务时空串 |
 * | [hint] | `MissionTaskSnapshot.hint` | `current?.hint`，无任务时空串 |
 * | [skillName] | `MissionTaskSnapshot.skillName` | `current?.skillName`，无任务时空串 |
 * | [tipId] | `MissionTaskSnapshot.tipId` | `current?.tipId`，无任务时空串 |
 * | [kind] | 诊断 / 日志 | `TaskKind.name`（`COMPOSITION` / `LEVEL` / `HEADROOM`），**空串 = 无任务** |
 * | [kindRawValue] | `MissionTaskSnapshot.kind` | **直接** `MissionKind(rawValue:)`，值为 `level` / `composition` / `headroom`，**空串 = 无任务 → 给 `nil`** |
 * | [ringProgress] | `MissionSnapshot.ringProgress` | `MissionEngine.ringProgress()`，已统一成 0..1（方向换算在 Kotlin 的 `TaskPicker.progressOf`），**不要再翻转** |
 * | [remainingSeconds] | `MissionSnapshot.remainingSeconds` | 向上取整的秒数，`IDLE` 为 0，见 [IosMissionBridge.tick] 的说明 |
 * | [awardedScore] | `MissionSnapshot.awardedScore` | 到位那一刻冻结的参考分；非 `ACHIEVED` / `SCORED` 为 0 |
 * | [scoreMeasured] | 成绩卡是否印数字 | `false` → 印「参考分 --」，**不要印 0** |
 * | [message] | `MissionSnapshot.message` | 任务阶段 = 副句；`SCORED` = 「你刚才做对了：技法名」 |
 * | [showsTaskCard] | 交叉校验 | `TASK_SHOWN` / `IN_PROGRESS` / `ACHIEVED` |
 * | [showsScoreCard] | 交叉校验 | 仅 `SCORED` |
 *
 * ## 两个枚举为什么各给两个字段
 *
 * `MissionState.name` 是 `IN_PROGRESS`（大写 + 下划线），而 Swift 的
 * `MissionPhase` rawValue 是 `inProgress`（小驼峰）。**`lowercased()` 修不好这件事** ——
 * 小写之后是 `in_progress`，`MissionPhase(rawValue:)` 返回 `nil`，卡片会在
 * 「正在判定」这个最需要显示的时刻整张消失，而且不报任何错。
 *
 * 所以两个口径都给：
 * - [phase] / [kind] 是 **Kotlin 口径**，用于 Swift 侧写 `switch`、打日志、跨端对齐；
 * - [phaseRawValue] / [kindRawValue] 是 **Swift 枚举 rawValue 口径**，
 *   直接喂 `MissionPhase(rawValue:)` / `MissionKind(rawValue:)`，零胶水。
 *
 * 两者由同一个 `when` 生成，不可能互相矛盾；`when` 覆盖 `MissionState` 全集且**不写
 * `else` 分支** —— 将来 commonMain 新增一个状态时，这个文件会**编译失败**，
 * 逼着改 iOS 侧，而不是静默地把新状态当成 `idle`。
 *
 * ## [showsTaskCard] / [showsScoreCard] 为什么不完全按「state 三选二」写
 *
 * 这两个字段是**派生量**，真相在 Swift 的 `MissionPhase.showsTaskCard` /
 * `showsScoreCard`，`MissionSnapshot` 自己也带同名计算属性。这里刻意与它对齐：
 * - `ACHIEVED` 阶段**要显示任务卡**（卡片就地变「到位了」，Android 的
 *   `renderTaskSurface()` 也把 ACHIEVED 归在「任务卡 VISIBLE」那一支）；
 * - 成绩卡**只在 `SCORED` 显示**，`ACHIEVED` 阶段显示成绩卡会和「到位了」同屏。
 * ⇒ 若 MissionModel 直接用 `MissionSnapshot` 的计算属性，本 DTO 这两个字段
 *   永远与之一致，可以只当断言用。
 */
class IosMissionDto(
    /** `MissionState.name`，大写蛇形。诊断口径，见类注释「两个枚举为什么各给两个字段」。 */
    val phase: String,
    /** `MissionPhase` 的 rawValue（`idle` / `shown` / `inProgress` / `achieved` / `scored`）。 */
    val phaseRawValue: String,
    /** 任务卡主句（动作口吻，≤10 字）。无任务时空串。 */
    val title: String,
    /** 任务卡副句（≤16 字）。无任务时空串。 */
    val hint: String,
    /** 技法短名，卡片角标用。无任务时空串。 */
    val skillName: String,
    /** 关联技法 id，用于「看这条技法」跳转。无任务时空串。 */
    val tipId: String,
    /** `TaskKind.name`（`COMPOSITION` / `LEVEL` / `HEADROOM`）。**空串 = 无任务**。 */
    val kind: String,
    /** `MissionKind` 的 rawValue（`level` / `composition` / `headroom`）。**空串 = 无任务**。 */
    val kindRawValue: String,
    /** 环的绿意进度 0..1，方向已由 `TaskPicker.progressOf` 统一。 */
    val ringProgress: Float,
    /** 距本阶段结束的剩余秒数，向上取整；`IDLE` 为 0。 */
    val remainingSeconds: Int,
    /** 到位那一刻冻结的参考分（0..100）。非 `ACHIEVED` / `SCORED` 恒为 0。 */
    val awardedScore: Int,
    /** 冻结那一刻的分数是否四维齐全。`false` 时成绩卡应显示「参考分 --」。 */
    val scoreMeasured: Boolean,
    /** 任务阶段 = 副句；`SCORED` = 「你刚才做对了：技法名」。 */
    val message: String,
    /** 是否显示任务卡：`TASK_SHOWN` / `IN_PROGRESS` / `ACHIEVED`。 */
    val showsTaskCard: Boolean,
    /** 是否显示成绩卡：仅 `SCORED`。 */
    val showsScoreCard: Boolean
) {
    companion object {

        /**
         * 无任务的哨兵值 —— 也是「第一次 tick 早于第一次 analyzeFrame」时的返回值。
         *
         * ★ 为什么必须有它：拿一份空 `FrameStats` + 空 `CompositionHint` 去喂状态机，
         *   `TaskPicker` 会按 0 值挑任务（`subjectConfidence = 0` 让构图任务直接不可行，
         *   但 `skinRatio = 0` 之外的路径仍可能派出一条基于全 0 几何量的任务卡），
         *   用户会看到一张"把主体挪到左上"却根本指不出主体在哪的卡。
         *   宁可这一 tick 什么都不显示。
         */
        val IDLE: IosMissionDto = IosMissionDto(
            phase = "IDLE",
            phaseRawValue = "idle",
            title = "",
            hint = "",
            skillName = "",
            tipId = "",
            kind = "",
            kindRawValue = "",
            ringProgress = 0f,
            remainingSeconds = 0,
            awardedScore = 0,
            scoreMeasured = false,
            message = "",
            showsTaskCard = false,
            showsScoreCard = false
        )
    }
}
