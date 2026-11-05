// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/Growth/ScoreHistoryStore.swift —— 参考分历史的落盘层（iOS 侧）。
//
// ★ 为什么这个文件几乎不写逻辑
//   「记什么、总分怎么算、超过 500 条丢谁、最近 N 天怎么切窗口」全部在 commonMain
//   （`ScoreHistory` / `ShotRecord`），Android 一直在用、有单测。iOS 侧缺的从来不是
//   算法，而是**一块能放 JSON 的地方 + 一条取数的出口**。⇒ 本文件只做两件事：
//     ① 读写 Application Support 下的一个 JSON 文件；
//     ② 把 `ScoreSnapshot` 的四维原样交给 `IosGrowthBridge.add(...)`。
//   任何「我自己加一下四个数」「我自己 filter 一下时间」的写法都是在造第二份口径，
//   而第二份口径与第一份分叉时**不会报错** —— 只会让成长页和成绩卡各说一套。
//
// ★ 为什么落盘在 Application Support 而不是 Documents
//   `Documents` 会被 iCloud 备份 + 在「文件」App 里可见。分数历史是**可从相册重建**的
//   使用记录（500 条 × ~100B ≈ 55KB），没有跨设备同步的价值，
//   塞进 iCloud 备份只会白占用户的配额。`Application Support` 是「App 自己的数据」
//   该待的地方，且默认不进「文件」App。
//
// ★ 为什么还要显式 `isExcludedFromBackup = true`
//   光换目录**不够**：`Application Support` 仍然参与 iCloud / iTunes 备份。
//   必须给文件打上 `URLResourceValues.isExcludedFromBackup`，iOS 才会跳过它。
//   注意 `URL.setResourceValues(_:)` 是**mutating** 方法且要求 `var` 的 `URL` ——
//   `let url` 上调用会编译失败（"cannot use mutating member on immutable value"）。
//
// ★ 为什么 `record` 之后**同步**写盘，而不是攒批 / 定时器
//   100 条记录约 10KB，一次同步 `write` 在真机上是亚毫秒级，卡不动任何东西。
//   反过来，攒批的代价是**真的会丢**：用户拍完一张、立刻从后台划掉 App，
//   攒批里的那一条就永远不存在了 —— 而「拍完就杀进程」是最常见的手机使用方式。
//   一次同步写换「绝不丢记录」，这个交换明显划算。
//
// ★ 为什么 `measured == false` 时仍然要把 `measured: false` 传下去
//   `ScoreSnapshot` 里未测维度的 `score` 恒为 0（见 `DimensionSnapshot.measured` 的
//   注释）。如果把 0 当「已测」传进 `add(...)`，common 侧会把它写成一条**真实的 0 分**，
//   于是 `toJson` 把它落盘、下次读回来已经无法分辨「没测到」与「测出来是 0」。
//   本仓已经因为「缺数据伪装成 0」被打回两次（成绩卡印假分数、`hasTotal` 口径），
//   这里是同一个坑的第三个入口。
//   门面自己也会兜一层（`if (levelMeasured) level else null`），但调用方**不许**
//   指望它兜 —— 传错的那一侧才是出错的那一侧。
//
// ★ 为什么 `revision` 是一个 `Int` 而不是把桥里的数组搬进 `@Published`
//   桥的数组（500 条 × 5 个字段）搬进 SwiftUI 状态意味着 **每次拍完照都要复制 2500 个
//   标量**，而且 Swift 侧从此有一份可能与 Kotlin 侧不同步的副本。
//   ⇒ 只发布一个自增计数，视图靠它触发重算；真正的数据永远现场问桥（单一真相）。
//
// ★ 为什么 `import Combine` 不能省
//   `ObservableObject` 与 `@Published` 定义在 **Combine**，不在 Foundation。
//   本仓刚因为漏了这一行让整个类型构造失败（`ScoreHistoryStore` 曾经只剩
//   Foundation + Shared）。`SwiftUI` 会间接带出 Combine，但这个文件不 import SwiftUI
//   （它是数据层，不该依赖 UI 框架），所以必须显式写。

import Combine
import Foundation
import Shared

// MARK: - 折线图上的一个点

/// 成长页折线图上的一个点。
///
/// ★ `total` 是 `Int?`（而不是 0）：`nil` 表示这条记录四维不全，**没有总分**。
///   折线图遇到 `nil` 必须**断开**，不能画成 0 ——
///   一条掉到 0 再弹回来的折线会被用户读成「那天我拍废了」，而事实是我们没测全。
///   （同一个口径在 common 侧：`ScoreHistory.recentSeries` 不补点、不插值。）
struct GrowthPoint: Identifiable {

    /// 稳定身份。用窗口内的序号，不掺时间戳 —— 两张照片可能落在同一毫秒。
    let id: Int

    /// 拍摄时刻（墙上时钟）。
    let timestamp: Date

    /// 总分；`nil` 表示四维不全、没有可显示的总分。
    let total: Int?
}

// MARK: - 落盘 + 取数

/// 参考分历史的 Swift 门面：一块磁盘位置 + 一条取数出口。
///
/// ★ `@MainActor` 与 `ScoreCoordinator` 同口径：本类型的全部方法都会碰
///   `IosGrowthBridge` 的可变内存态（`ScoreHistory` 内部是 `MutableList`，
///   门面注释里写明「假定在主线程调用」）。钉在主 actor 上比每个方法各自小心更不容易错。
@MainActor
final class ScoreHistoryStore: ObservableObject {

    // MARK: 常量

    /// 历史文件名。
    ///
    /// ★ 名字里带 `shot_` 前缀是为了在 Application Support 目录里一眼看出是哪个模块的
    ///   文件 —— 那个目录将来还会有别的数据，而 `history.json` 这种名字
    ///   半年后没人敢删、也没人知道是谁写的。
    private static let fileName = "shot_history.json"

    // MARK: 发布状态

    /// 记录变化计数。每次 `record` 之后 +1。
    ///
    /// ★ 视图不需要读它的值，只需要「它变了 ⇒ 我重算」。真正的数据一律现场问桥
    ///   （见文件头「为什么 `revision` 是一个 Int」）。
    @Published private(set) var revision: Int = 0

    // MARK: 私有状态

    /// 历史文件的绝对路径（`Application Support/shot_history.json`）。
    ///
    /// ★ `let`：路径整生命周期不变。变的是文件内容。
    private let fileURL: URL

    // MARK: 生命周期

    /// 装载历史。
    ///
    /// ★ 读盘失败 / 文件不存在都走 `load(json: nil)`：门面把它当作「还没存过」，
    ///   等价于一份空历史。**不要**为此伪造 `"{}"` 之类的占位串 ——
    ///   空串在门面里也是「没存过」，但伪造 JSON 会让「文件坏了」与「文件是空的」
    ///   这两件事混成一件。
    ///
    /// ★ 解析失败（`lastLoadError()` 非空）**只写控制台**，不做 UI 提示、不重试。
    ///   理由：这是诊断信息，不是用户能处理的事。用户看到「历史读不出来」也做不了什么，
    ///   而 common 侧已经把坏历史整体作废并返回空历史（口径：错的 delta 比没有 delta
    ///   更伤）。留一行日志是为了现场反馈「我的记录没了」时查得动，
    ///   不是为了教用户去修 JSON。
    init() {
        let url = ScoreHistoryStore.makeFileURL()
        fileURL = url

        let json = ScoreHistoryStore.readHistoryText(at: url)
        IosGrowthBridge.shared.load(json: json)

        let failure = IosGrowthBridge.shared.lastLoadError()
        if !failure.isEmpty {
            print("[ScoreHistory] 分数历史读盘失败，已按空历史处理：\(failure)")
        }
    }

    // MARK: - 写入

    /// 拍成一张时调一次。
    ///
    /// ★ 必须是**按快门那一刻冻结的快照**，不是实时读数。`ScoreSnapshot` 是值类型，
    ///   调用点在按快门时拷一份传进来即可；如果这里去读
    ///   `coordinator.score`，拿到的会是「用户看复盘卡时」的最新一帧 ——
    ///   于是历史里记的分与复盘卡上显示的分不是同一个，而两张照片之间看不出差别。
    ///
    /// ★ 不自己算 `total`：`ShotRecord.of(...)` 在 common 侧算，任一维未测就是 null。
    ///   Swift 侧再算一遍就是第二份加法（而且必然要和 `ScoreBoard` 的权重对齐，
    ///   那正是两端分叉的起点）。
    ///
    /// - Parameter snapshot: 按快门那一刻的四维参考分快照。
    func record(_ snapshot: ScoreSnapshot) {
        // 墙上时钟毫秒。★ `timeIntervalSince1970` 是 `Double`（秒），
        // 乘 1000 之后才转 `Int64`；顺序写反（先转再乘）会丢掉毫秒精度，
        // 而「最近 N 天」的窗口边界正是按毫秒比的。
        let timestamp = Int64(Date().timeIntervalSince1970 * 1000)

        IosGrowthBridge.shared.add(
            timestamp: timestamp,
            // Swift `Int` → Kotlin `Int`：必须显式 `Int32(...)`，无隐式转换。
            level: Int32(snapshot.level.score),
            // ★ 原样透传 `measured`（见文件头第 4 条）：未测时 `score` 是 0，
            //   但 `measured: false` 会让门面把它转成 null，0 不会进磁盘。
            levelMeasured: snapshot.level.measured,
            exposure: Int32(snapshot.exposure.score),
            exposureMeasured: snapshot.exposure.measured,
            composition: Int32(snapshot.composition.score),
            compositionMeasured: snapshot.composition.measured,
            clarity: Int32(snapshot.clarity.score),
            clarityMeasured: snapshot.clarity.measured
        )

        revision &+= 1
        // ★ 同步落盘，见文件头「为什么 record 之后同步写盘」。
        persist()
    }

    /// 把当前历史序列化并写盘。
    ///
    /// 序列化本身在 common 侧（`ScoreHistory.toJson`，手写、有单测），
    /// 这里只负责「写到哪儿、失败怎么办」。
    ///
    /// ★ 失败只打日志、不抛、不弹窗：一次写真失败（磁盘满、沙盒异常）时，
    ///   用户在拍照这条主线上什么都没做错，界面不该因此弹出任何东西。
    ///   代价是这一条记录只在内存里（本次会话内仍然看得见），下次写盘会再试一次。
    func persist() {
        let json = IosGrowthBridge.shared.saveJson()

        // 目录可能还不存在（首次启动）—— `withIntermediateDirectories: true`
        // 会连同中间层一起建，已存在时是无操作。
        let directory = fileURL.deletingLastPathComponent()
        do {
            try FileManager.default.createDirectory(
                at: directory,
                withIntermediateDirectories: true,
                attributes: nil
            )
            try json.write(to: fileURL, atomically: true, encoding: .utf8)
        } catch {
            print("[ScoreHistory] 分数历史写盘失败：\(error)")
            return
        }

        // ★ 写成功之后才打排除备份标记：文件还不存在时 `setResourceValues` 会抛。
        ScoreHistoryStore.markExcludedFromBackup(fileURL)
    }

    // MARK: - 读取

    /// 记录条数（环形覆盖后 ≤ `maxRecords()`）。
    var count: Int {
        Int(IosGrowthBridge.shared.count())
    }

    /// 首末对比摘要。
    ///
    /// ★ 只读、不缓存成 Swift 属性：common 那边是真相，缓存一份就会出现
    ///   「拍完一张、卡片还是旧的」这种只在快速连拍时出现的错。
    ///   调用它很便宜（一次 500 条以内的遍历）。
    var growth: IosGrowthDto {
        IosGrowthBridge.shared.growth()
    }

    /// 环形上限（当前 500，由 common 的 `ScoreHistory.MAX_RECORDS` 给）。
    ///
    /// ★ 不写死 500：common 侧哪天调成 300，写死的那个数字会悄悄错，
    ///   表现为「记到 500 条之后界面还说没满」。
    /// - Returns: 上限条数。
    func maxRecords() -> Int {
        Int(IosGrowthBridge.shared.maxRecords())
    }

    /// 最近 `days` 天的点，供折线图。
    ///
    /// ★ 窗口筛选**只用** common 的 `ScoreHistory.recentSeries(days, now)`，
    ///   不在 Swift 侧另写一套「最近 N 天」的过滤。两套实现必然在
    ///   「窗口边界算不算」「天数上限」「时区」上分叉，而分叉不会报错 ——
    ///   只会让两端的折线图点数不一样。
    ///
    /// ★ 返回值保持**时间升序**（common 侧的快照就是升序），折线图的 X 轴依赖这一点。
    ///
    /// ★ 逐下标取数：`recentCount` 先把窗口算好并缓存，紧接着的三个访问器读的是同一份
    ///   快照。**这三次调用必须在同一轮里完成**，中间不要插入 `record`
    ///   （那会换掉窗口，让 count 与各访问器错位、画出越界的点）。
    ///
    /// - Parameters:
    ///   - days: 窗口天数（> 0；门面会把越界值钳到 1..3650）。
    ///   - now: 当前时刻（墙上时钟，与记录里的 `timestamp` 同一把尺子）。
    /// - Returns: 窗口内的点；一个点都没有时是空数组。
    func recentPoints(days: Int, now: Date) -> [GrowthPoint] {
        let nowMilliseconds = Int64(now.timeIntervalSince1970 * 1000)
        let total = Int(
            IosGrowthBridge.shared.recentCount(days: Int32(days), now: nowMilliseconds)
        )
        guard total > 0 else { return [] }

        var points: [GrowthPoint] = []
        points.reserveCapacity(total)
        for offset in 0..<total {
            let index = Int32(offset)
            // ★ 先问「测了没」，再取数。`recentTotalAt` 在未测时返回 0，
            //   直接用它会把「没测全」画成「0 分」（见 `GrowthPoint.total` 的注释）。
            let measured = IosGrowthBridge.shared.recentTotalMeasuredAt(index: index)
            let value: Int? = measured
                ? Int(IosGrowthBridge.shared.recentTotalAt(index: index))
                : nil
            let milliseconds = IosGrowthBridge.shared.recentTimestampAt(index: index)
            points.append(
                GrowthPoint(
                    id: offset,
                    timestamp: Date(timeIntervalSince1970: Double(milliseconds) / 1000.0),
                    total: value
                )
            )
        }
        return points
    }

    // MARK: - 私有工具

    /// 历史文件的绝对路径。
    ///
    /// ★ 用 `urls(for:in:).first`（不抛异常）而不是
    ///   `url(for:in:appropriateFor:create:)`：后者要 `try`，而这里拿不到目录时
    ///   会退化到临时目录继续跑 —— 让「拍照」这条主线因为一个路径查不到而失败，
    ///   比把历史写进临时目录严重得多。
    ///
    /// - Returns: `Application Support/shot_history.json`。
    private static func makeFileURL() -> URL {
        let base = FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        ).first
        let directory = base ?? FileManager.default.temporaryDirectory
        return directory.appendingPathComponent(fileName, isDirectory: false)
    }

    /// 读历史原文。
    ///
    /// ★ 文件不存在返回 `nil`（= 还没存过），不返回空串再让上层转义：
    ///   `load(json:)` 对 `nil` 与空串的处理虽然一样，但「文件不存在」与
    ///   「文件存在但是空的」是两件事，读的时候区分开，将来排查才有的查。
    ///
    /// ★ 用 `String(contentsOf:encoding:)` 而不是 `Data(contentsOf:)`：
    ///   本仓有零网络门禁（`tools/ci/patterns/network-source.txt`），
    ///   `Data(contentsOf:)` 正是被禁的特征串 —— 而且这里本来就只要文本。
    ///
    /// - Parameter url: 历史文件路径。
    /// - Returns: 文件原文；不存在或读不出来时为 `nil`。
    private static func readHistoryText(at url: URL) -> String? {
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        return try? String(contentsOf: url, encoding: .utf8)
    }

    /// 给文件打「不进 iCloud 备份」标记。
    ///
    /// ★ `setResourceValues(_:)` 的签名是
    ///   `mutating func setResourceValues(_ values: URLResourceValues) throws` ——
    ///   **`mutating` 作用在 `URL` 自己身上，形参是按值传入、不是 `inout`**。
    ///   所以需要 `var` 的是 `url`，不是 `values`。写成 `setResourceValues(&values)`
    ///   会直接编译失败：`'&' used with non-inout argument of type 'URLResourceValues'`。
    ///   （`values` 之所以也是 `var`，只是因为它要先被赋值 `isExcludedFromBackup`。）
    ///
    /// ★ 失败只打日志：备份策略是「省钱」层面的优化，不该因为它失败而中断拍照。
    ///
    /// - Parameter target: 要打标记的文件路径。
    private static func markExcludedFromBackup(_ target: URL) {
        var url = target
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        do {
            try url.setResourceValues(values)
        } catch {
            print("[ScoreHistory] 设置「不进备份」标记失败：\(error)")
        }
    }
}
