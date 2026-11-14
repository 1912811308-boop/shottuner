// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/Growth/DexUnlockStore.swift —— 技法图鉴解锁状态的落盘层（iOS 侧）。
//
// ★ 这个文件存在的理由（G3「未完成的册子」在 iOS 侧的唯一缺口）
//   Android 的图鉴页顶部有一行「已解锁 N / 83 + 进度条」（`MainActivity.renderDexProgress`），
//   列表里已解锁的条目还带一个绿色「已解锁」标记。iOS 侧此前只有一行
//   「共 83 条 · 与 Android 版同源，照片不上传」—— **一条解锁状态都没有**。
//   缺的不是内容（83 条早在 commonMain），也不是判定（`shouldUnlock` 也在 commonMain），
//   而是「一块能存这 64 个 id 的地方 + 一条问 Kotlin 要结果的出口」。
//   ⇒ 本文件只做两件事：
//     ① 读写一份 `UserDefaults`：已解锁 id 列表 + 上次解锁日期；
//     ② 把「本次这张照片的四维参考分」原样交给
//        `TechniqueDexBridge.pickUnlockTargetByDimension(...)`，拿回一个 id 就落盘。
//
// ★ 铁律一：判定一行都不在这儿写
//   「多少分算达标」「一天最多解锁几条」「哪一维先解锁」全部在 common
//   （`shouldUnlock` / `DexThresholds` / `TechniqueDex.nextUnlockable`），
//   Android 的 `DexStore` 调的也是同一个函数。Swift 侧再写一份
//   `if score > 24 { unlock() }` 就是第二份口径 —— 而第二份口径与第一份分叉时
//   **不会报错**，只会让同一张照片在两端解锁不同的条目。这类 bug 只有用户会发现。
//   ⇒ 本文件里凡是「判断」都只做一件事：把参数摆好、把结果记下来。
//
// ★ 铁律二：未测的维度传 -1，不传 0
//   `DimensionSnapshot` 的 `score` 在 `measured == false` 时恒为 0（见 `ScoreTypes.swift`）。
//   把那个 0 传下去，common 会当成「测出来是 0 分」—— 本仓已经因为「缺数据伪装成 0」
//   被打回两次（成绩卡印假分数、`hasTotal` 口径），这里是同一个坑的第四个入口。
//   `-1` 是 common 的既有约定（`shouldUnlock` 的 `dimScore < 0` = 未测），
//   `ScoreBoard` 也用它。
//
// ★ 为什么用 UserDefaults，而不是像 `ScoreHistoryStore` 那样写 JSON 文件
//   解锁状态是一组**布尔**（哪些 id 解锁过）+ 一个日期，没有任何内部结构。
//   JSON 方案要自己写序列化、自己处理 tmp+rename 原子写、自己处理「文件坏了」——
//   而 `ScoreHistoryStore` 之所以那么做，是因为它是一份有内部结构的文档
//   （坏一点整份作废）。这里 `UserDefaults` 自己就保证了写的原子性与线程安全，
//   能少写的代码就不写。（这也是 Android 侧的选择：那边用 `SharedPreferences`，
//   连文件格式都不用自己定。）
//
// ★ 为什么键名、域（suite）名要和 Android 逐字对齐
//   域 = `technique_dex`（Android 的 SharedPreferences 文件名），
//   键 = `unlocked` / `last_unlock_date`（`DexStore.KEY_UNLOCKED` / `KEY_LAST_DATE`）。
//   两端存的是同一件事，名字对上之后：「这个键是干嘛的」不用查代码，
//   将来要做跨端迁移（比如从 Android 导一份进度过来）也有个明确的落点。
//   ★ 值类型不同（Android 是 StringSet，iOS 是 [String]）是平台差异，不是口径差异：
//   两端存的都是「id 的集合」本身，不是位图字符串 —— Android 那边
//   `DexStore` 文件头第 2 条写了为什么不用位图（知识库中间插一条会静默错位）。
//
// ★ 为什么「已解锁数」要去问 Kotlin，而不是数一下本地数组的长度
//   本地数组里可能残留旧版本的 id（某条技法改过名 / 被删）。直接取
//   `ids.count` 会让首页出现「85 / 83」这种不成立的分数。Kotlin 侧的
//   `unlockedCount(joinedUnlockedIds:)` 会先与图鉴求交再数（与 Android 的
//   `DexStore.unlockedCount` 同口径）。**宁可少算一条，也不给用户一个不成立的分母。**
//
// ★ 为什么时间/日期由 Swift 给
//   `DexClock`（`yyyyMMdd`）在 androidMain —— 它用 `java.text.SimpleDateFormat`，
//   Kotlin/Native 里没有。而 iosMain 一旦 import `platform.Foundation`，
//   就会失去 Windows 上「把 iosMain 临时拷进 androidMain 跑单测」这条唯一的
//   编译验证路径（本仓没有 Mac CI）。所以日期字符串在这里用 `DateFormatter` 生成，
//   格式与 Android 逐字一致：`yyyyMMdd`、本地时区、公历、`en_US_POSIX`。
//   ★ 公历 + POSIX 是必须的：用户的系统日历可能是佛历 / 和历，
//   `yyyy` 会给出 2568 这种数，两端的「今天」就永远对不上（而且不报错）。

import Combine
import Foundation
import Shared

// MARK: - 落盘 + 解锁触发

/// 技法图鉴解锁状态的 Swift 门面。
///
/// ★ 为什么 `@MainActor`（与 `ScoreHistoryStore` 同口径）：
/// 本类型会调 `TechniqueDexBridge`，而图鉴门面背后是 `TechniqueDex` 的 lazy 缓存
/// （`by lazy`），首次访问会建 83 条的索引表。钉在主 actor 上比「每个方法各自小心」
/// 更不容易错，而且本类型的唯一调用方是 SwiftUI 视图（本来就在主线程）。
@MainActor
final class DexUnlockStore: ObservableObject {

    // MARK: - 常量

    /// `UserDefaults` 的域（suite）名。与 Android 的 SharedPreferences 文件名一致。
    private static let suiteName = "technique_dex"

    /// 已解锁 id 列表的键。与 Android 的 `DexStore.KEY_UNLOCKED` 同值。
    private static let keyUnlocked = "unlocked"

    /// 上次解锁日期的键。与 Android 的 `DexStore.KEY_LAST_DATE` 同值。
    private static let keyLastDate = "last_unlock_date"

    /// 「这一维没测到」的占位值。
    ///
    /// ★ 与 common 的约定一致（`shouldUnlock` 的 `dimScore < 0` = 未测），
    ///   **不是** 0 —— 0 是「测出来 0 分」。见文件头铁律二。
    private static let unmeasuredScore: Int32 = -1

    // MARK: - 对外状态

    /// 图鉴总条数（首页那个「/ 83」的分母）。由 Kotlin 门面给，不写死数字。
    let total: Int

    /// 已解锁条数（分子）。只统计**图鉴里仍存在**的 id，见文件头最后一条。
    @Published private(set) var unlockedCount: Int = 0

    /// 已解锁条目的 id 集合。列表打绿标用。
    ///
    /// ★ 与 `unlockedCount` 同源（都由 `reload()` 从 Kotlin 门面问出来），
    ///   所以「进度条说 3、列表里亮了 2 条」这种错位不可能发生。
    @Published private(set) var unlockedIds: Set<String> = []

    // MARK: - 私有状态

    /// 落盘位置。注入成实例属性是为了将来测试能换成别的域（现在没有测试）。
    private let defaults: UserDefaults

    /// 图鉴里全部 id。写盘时用它把旧版本残留的 id 清掉
    /// （Android 的 `DexStore.unlockLocked` 也是「顺手把不在图鉴里的旧 id 清掉」）。
    private let dexIds: Set<String>

    // MARK: - 生命周期

    /// 装载。
    ///
    /// ★ 不在这里触发任何解锁：装载只读不写。解锁只有**一个**入口
    ///   `recordCapture(_:)`，触发点是 `ContentView.capture()` 的成功回调。
    /// ★ 也不许加「打开某页时补结算一次」这类第二入口：它会在「点开册子」这个动作上
    ///   花掉当天的额度，于是用户拍到达标的那一张时**没有任何提示**，
    ///   反而是几天后随手点开册子才悄悄解锁一条 ——
    ///   解锁时机与「我这一张拍得好」之间的因果一旦断了，图鉴就没有意义了。
    init() {
        // 取不到自定义域就退化到 standard —— 宁可把进度写进默认域，
        // 也不要让「图鉴」因为一个域取不到而完全不工作（读不到就永远是 0 / 83）。
        self.defaults = UserDefaults(suiteName: DexUnlockStore.suiteName) ?? .standard

        // Kotlin 的 Int 映射到 Swift 是 Int32，这里一次转成 Int 供 Swift 侧使用。
        let total = Int(TechniqueDexBridge.shared.count())
        self.total = total

        var ids: Set<String> = []
        ids.reserveCapacity(total)
        for offset in 0..<total {
            ids.insert(TechniqueDexBridge.shared.idAt(index: Int32(offset)))
        }
        self.dexIds = ids

        reload()
    }

    // MARK: - 读取

    /// 某条是否已解锁。详情/列表都走它，不要在视图里自己拼 id。
    ///
    /// - Parameter id: 技法 id（`TechniqueDexBridge.idAt(index:)` 给的那个）。
    /// - Returns: 已解锁为 `true`；id 为空串或不在图鉴里恒为 `false`。
    func isUnlocked(id: String) -> Bool {
        guard !id.isEmpty else { return false }
        return unlockedIds.contains(id)
    }

    /// 解锁进度条的比例（0...1）。分母为 0 时返回 0，绝不做除零。
    var progress: Double {
        guard total > 0 else { return 0 }
        let ratio = Double(unlockedCount) / Double(total)
        // 存储里若混进重复 id，理论上分子可能大于分母 —— 这里夹一下，
        // 免得进度条画出界（`unlockedCount` 内部已去重，这是第二层保险）。
        return min(max(ratio, 0), 1)
    }

    // MARK: - 写入

    /// 拍成一张时调一次：按这张照片的四维参考分，决定要不要解锁一条技法。
    ///
    /// ★ 调用时机必须与 `ScoreHistoryStore.record(...)` **同一处**、传**同一份快照**：
    ///   那份快照是「按下快门那一刻冻结的四维分」。传实时读数会让图鉴按
    ///   「用户看复盘卡时的那一帧」结算，与实际存下来的那张照片不是同一件事。
    ///
    /// ★ 返回值只用来做「图鉴 +1」提示（Android 的复盘卡就这么用），
    ///   **不要**拿它做任何判断 —— 空串就是「这次没解锁」，没有别的含义。
    ///
    /// - Parameter snapshot: 按快门那一刻的四维参考分快照。
    /// - Returns: 本次新解锁的技法 id；没解锁时为空串。
    @discardableResult
    func recordCapture(_ snapshot: ScoreSnapshot) -> String {
        return settle(
            levelScore: DexUnlockStore.scoreOrUnmeasured(snapshot.level),
            exposureScore: DexUnlockStore.scoreOrUnmeasured(snapshot.exposure),
            compositionScore: DexUnlockStore.scoreOrUnmeasured(snapshot.composition),
            clarityScore: DexUnlockStore.scoreOrUnmeasured(snapshot.clarity)
        )
    }

    // MARK: - 私有工具

    /// 判定 + 记账的唯一路径。
    ///
    /// ★ 判定**只问 Kotlin**：`pickUnlockTargetByDimension` 内部就是
    ///   「按 水平→曝光→构图→清晰 的顺序找候选，再调 common 的 `shouldUnlock`」，
    ///   与 Android 的 `MainActivity.unlockDexByDimension` + `DexStore.tryUnlockByDimension`
    ///   逐行等价。这里不做任何本地判断。
    ///
    /// - Parameters:
    ///   - levelScore: 水平维度参考分；`-1` = 未测
    ///   - exposureScore: 曝光维度参考分；`-1` = 未测
    ///   - compositionScore: 构图维度参考分；`-1` = 未测
    ///   - clarityScore: 清晰维度参考分；`-1` = 未测
    /// - Returns: 该解锁的 id；不该解锁时为空串。
    private func settle(
        levelScore: Int32,
        exposureScore: Int32,
        compositionScore: Int32,
        clarityScore: Int32
    ) -> String {
        let target = TechniqueDexBridge.shared.pickUnlockTargetByDimension(
            levelScore: levelScore,
            exposureScore: exposureScore,
            compositionScore: compositionScore,
            clarityScore: clarityScore,
            // 逗号拼接的 id 串（跨语言只传标量，见门面文件头）。
            joinedUnlockedIds: storedIds().joined(separator: ","),
            // 从未解锁过传空串：`shouldUnlock` 把空串当「没有上次」（它的 isNullOrBlank）。
            lastUnlockDate: lastUnlockDate ?? "",
            today: DexUnlockStore.today()
        )
        guard !target.isEmpty else { return "" }
        persist(unlocking: target)
        return target
    }

    /// 记一条解锁：id 追加进列表、写入今天的日期，然后刷新对外状态。
    ///
    /// ★ 先写 id 再写日期，两步之间没有事务。极端情况下（第二次写失败）
    ///   结果是「id 存了、日期没存」—— 下次打开会允许再解锁一条，
    ///   也就是多解锁一条，**不会**少记也不会记错条目。这个失败方向是刻意的。
    ///
    /// - Parameter id: 要记的技法 id（`settle` 已保证它不是空串、且在图鉴里）。
    private func persist(unlocking id: String) {
        let today = DexUnlockStore.today()
        // 拿不到日期就不落盘：否则会写进一条「没有日期」的解锁，
        // 而 `last_unlock_date` 空着就等于放弃了「每天一条」的限制。
        guard !today.isEmpty else {
            print("[DexUnlock] 取不到今天的日期，本次不记录解锁（id=\(id)）")
            return
        }

        var ids = storedIds()
        if !ids.contains(id) {
            ids.append(id)
        }
        defaults.set(ids, forKey: DexUnlockStore.keyUnlocked)
        defaults.set(today, forKey: DexUnlockStore.keyLastDate)

        reload()
    }

    /// 从存储里读已解锁 id，并滤掉图鉴里已不存在的那些。
    ///
    /// ★ 过滤是「顺手清垃圾」而不是「纠正数据」：读出来的这份**不写回**磁盘
    ///   （写回只发生在 `persist`），所以一次只读操作不会产生写入。
    ///
    /// - Returns: 仍在图鉴里的已解锁 id；顺序沿用存储里的顺序。
    private func storedIds() -> [String] {
        let raw = defaults.stringArray(forKey: DexUnlockStore.keyUnlocked) ?? []
        var result: [String] = []
        result.reserveCapacity(raw.count)
        for id in raw where !id.isEmpty && dexIds.contains(id) {
            // 存储里理论上不会有重复，但手工改过 plist / 迁移脚本写错时会有。
            // 重复 id 会让「列表里 2 条亮、进度条说 1」变成「进度条说 2」，
            // 所以这里也去一次重。
            if !result.contains(id) {
                result.append(id)
            }
        }
        return result
    }

    /// 上次解锁日期（`yyyyMMdd`）；从未解锁过为 `nil`。
    private var lastUnlockDate: String? {
        let value = defaults.string(forKey: DexUnlockStore.keyLastDate) ?? ""
        return value.isEmpty ? nil : value
    }

    /// 重新问 Kotlin 要「已解锁几条 / 哪些已解锁」，并发布出去。
    ///
    /// ★ 每次都现问，不在 Swift 侧缓存一份「我自己算的解锁集合」：
    ///   缓存就会出现「拍完一张、进度条还是旧的」这种只在快速连拍时出现的错。
    private func reload() {
        let joined = storedIds().joined(separator: ",")
        unlockedCount = Int(TechniqueDexBridge.shared.unlockedCount(joinedUnlockedIds: joined))

        var ids: Set<String> = []
        ids.reserveCapacity(unlockedCount)
        for offset in 0..<total {
            let index = Int32(offset)
            // ★ 逐条问门面「这一条解锁了吗」而不是拿本地 `storedIds()` 做集合判断：
            //   后者会在 Swift 侧重现一遍「与图鉴求交」的规则，等于第二份口径。
            if TechniqueDexBridge.shared.isUnlockedAt(index: index, joinedUnlockedIds: joined) {
                ids.insert(TechniqueDexBridge.shared.idAt(index: index))
            }
        }
        unlockedIds = ids
    }

    /// 这张快照里的某一维参考分；未测时给 `-1`。
    ///
    /// - Parameter dimension: 四维之一的分维度快照。
    /// - Returns: 参考分；`measured == false` 时是 `-1`（**不是** 0）。
    private static func scoreOrUnmeasured(_ dimension: DimensionSnapshot) -> Int32 {
        return dimension.measured ? Int32(dimension.score) : unmeasuredScore
    }

    // MARK: - 日期

    /// `yyyyMMdd` 格式化器。
    ///
    /// ★ `locale = en_US_POSIX` + `calendar = .gregorian`：用户的系统日历可能是
    ///   佛历 / 和历 / 民国纪年，那样 `yyyy` 会给出 2568、令和 6、113 这类值，
    ///   与 Android 的 `SimpleDateFormat`（公历）永远对不上 —— 而且**不会报错**，
    ///   只会让「每天最多一条」静默失效（每天都能解锁一条）。
    /// ★ 时区用系统默认值（与 Android 的 `SimpleDateFormat` 默认值一致）：
    ///   用户跨时区旅行时两端的「今天」可能差一天，这是**日期口径**本身的性质，
    ///   不是 bug —— 换 UTC 反而会让晚上 8 点之后拍的照算到「明天」。
    /// ★ 静态实例：`DateFormatter` 创建很贵，而这里一天最多用几次；
    ///   它是 `@MainActor` 类的静态成员，不存在跨线程共享问题。
    private static let dayFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        // 与 `DexClock.PATTERN` 逐字一致 —— 这是存储键的口径，两端必须相同。
        formatter.dateFormat = "yyyyMMdd"
        return formatter
    }()

    /// 今天（`yyyyMMdd`，本地时区）。
    ///
    /// - Returns: 例如 `"20250601"`。
    private static func today() -> String {
        return dayFormatter.string(from: Date())
    }
}
