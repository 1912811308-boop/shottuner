// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/Camera/VisionSceneMap.swift —— Vision 标识符 → common `AiScene` 的映射表。
//
// ★★★ 本表**尚未真机标定**，当前是**空表**。★★★
//
// 为什么要空着（这是本文件最重要的一件事）：
//   架构师对这张表的要求原文是「**映射表真机采集后固化，不凭记忆写**」
//   （docs/IOS-ARCH.md §10.2.3 / docs/IOS-ARCH-EXEC.md T11）。
//   Vision 的分类词表是 **1,303 个标识符的层级树**，且**随系统版本变化** ——
//   凭记忆写一批「大概的」标识符，等于在真机上**给出错误的场景判断**，
//   那比老老实实返回「未识别」**更糟**（用户会照着一份错的建议值去调参数）。
//   而我们**手上没有真机**：clover 只有 Android 机，也没有 Mac（见 docs/IOS-PRD.md §7.2）。
//   ⇒ 所以本文件交付的是：**表结构 + 匹配规则 + 多标签聚合算法 + 阈值常量 + 采集工具**，
//     **表的取值留空**。真机采到数据后回填（见 T22 · CAL-1）。
//
// ★ 标定前的安全路径（必须成立，且已写进代码）：
//   1. 表为空 ⇒ 任何 Vision 标识符都命中不了 ⇒ 场景判定退回**人脸证据**；
//   2. 人脸证据也没有 ⇒ 场景 = `AiScene.UNKNOWN`，置信度 = 0.30；
//   3. 全程**不会静默给出错误分类**，也**不会崩**（空表在代码里是合法的常态，不是异常）。
//
// ★ 为什么「前缀匹配 + 多标签聚合」是这张表的实现方式（docs/IOS-ARCH.md §10.2.2）：
//   1. **前缀匹配，不做全等** —— Vision 词表是层级树，一次 revision 变化就可能让全等匹配整片失效；
//      前缀匹配（`animal` → 宠物）只增不改。
//   2. **多标签必须显式聚合，禁止取 `results[0]`** —— Vision 是多标签网络，
//      `results[0]` 往往是最泛化的标签（`object` / `scene`），
//      直接取第一个会把「一只狗在草地上」判成「风景」。
//      本文件的 `aggregate(_:table:minimumConfidence:)` 实现的就是这条规则：
//      **逐条命中 → 取置信度最高者**。
//   3. **置信度阈值独立标定** —— 阈值全部在本文件（iOS 侧自有常量表），
//      **不照搬 Android 的 0.5f**（Vision 常见 0.77 已是强结果，0.5f 会静默返回空结果）。
//
// ★ 跨语言边界（本文件是少数几个直接 `import Shared` 的地方）：
//   场景值**直接产出 common 的 `AiScene`**（`shared/src/commonMain/.../analysis/AiVisionModels.kt`），
//   **不在 Swift 侧另建一个枚举**，也不做「String → 枚举」的二次中转 ——
//   那样将来两端要同步维护两张表，是纯粹的负债。
//
// ★ 跨语言小坑（写在这里，免得后人重踩）：
//   Kotlin 的枚举导出到 Swift 是**一个类 + 一组类属性**，不是 Swift 原生 enum：
//     `AiScene.portrait` / `AiScene.group` / `AiScene.unknown` …
//   ⇒ ① 比较要用 `==`（AnyObject 的 `==`，走 `isEqual:`），**不能用 `switch` 的穷尽匹配**；
//     ② `Optional<AiScene>` 之间**不能**用 `==` 比较（那要求 `Equatable` 协议），
//        所以下面的聚合逻辑用 `hasBest` 标志位而不是 Optional 比较。

import Foundation
import Shared

// MARK: - 阈值表（iOS 侧自有）

/// iOS 侧（Vision）自己的阈值常量表。
///
/// ★ 为什么这些常量住在 iOS 侧、而不是搬到 common：
///   与平台绑定的是**词表**与**阈值**，不是枚举本身（docs/IOS-ARCH.md §2.5）。
///   Android 用 ML Kit 的英文词表与它自己的阈值，iOS 用 Vision 的层级词表与它自己的阈值 —— 两者天然不同。
///   把它们塞进 commonMain 只会让两端互相污染，改一个动另一个。
enum VisionSceneThresholds {

    // ── ① 分类置信度阈值（★ 本任务的核心交付之一：独立标定，不照搬 0.5f）──

    /// 参与场景判定的 Vision 标签最低置信度（**全局默认值**）。
    ///
    /// ★ **为什么不照搬 Android 的 `0.5f`**：
    ///   Android 的 0.5f 是 **ML Kit `ImageLabeler`** 的选项（`AiVision.kt`），
    ///   而两者的置信度分布根本不是一回事：
    ///   ML Kit 的强结果常见 0.98，**Vision 的强结果常见 0.77**。
    ///   照搬 0.5f 的话，Vision 会**静默返回空结果**（不是报错，是「什么都没识别出来」）——
    ///   这种失败最难查，因为代码看起来完全正常。
    ///   本项目取 **0.25** 作为**起始值**（docs/IOS-ARCH.md §10.2.2 规则 3 / docs/IOS-PRD.md J7 ④），
    ///   真正固化由 T22 · CAL-1 真机标定后回填。
    ///
    /// ★ 它是**默认值**而不是**下限**：
    ///   每一族可以在自己的表项里写更严的阈值（见 `Entry.minimumConfidence`）；
    ///   某族若确实需要比 0.25 更松，由标定结果说了算 —— 全局值不应该把族的自主权堵死。
    static let minimumClassificationConfidence: Float = 0.25

    // ── ② 人脸阈值（与 Android 侧对齐，使两端的 `AiVisionResult` 语义可比）──

    /// 判定「人像」所需的最小人脸面积占比（占画面面积）。
    ///
    /// ★ 与 Android 侧 `AiVision.kt` 的 `0.012f` **一致**，且 common 的
    ///   `SceneClassifier.classify(s:ai:)` 也用 `0.012f` 判断「AI·人脸」。
    ///   ⇒ 这是**跨端契约**，不是平台差异，必须一致。
    static let portraitMinimumFaceArea: Float = 0.012

    /// 判定「合影」所需的最少人脸数（与 Android 侧一致）。
    static let groupMinimumFaceCount: Int = 3

    /// 人脸框最小宽度占画面宽度的比例。
    ///
    /// ★ 对齐 ML Kit 的 `setMinFaceSize(0.06f)`：ML Kit 内置了这个下限，
    ///   Vision 没有对应参数，所以**在 iOS 侧自己补一道**，
    ///   否则两端的脸数会因「小脸算不算」而对不上（影响验收 ② 的 < 5% 对照）。
    ///   ⚠️ ML Kit 文档把该比例描述为「相对图像宽度」，而实现里用的是
    ///   「相对图像短边」—— 竖幅图上两者不同，真机对照（CAL-2）时要留意。
    static let minimumFaceWidthRatio: Float = 0.06

    // ── ③ 场景判定置信度（供 common `SceneClassifier` 的 0.85 / 0.60 门槛使用）──
    //
    // ★ 与 ① 完全不同的一类阈值，务必分清：
    //   ① 是「**Vision 标签**要不要采信」（识别层阈值，平台专属，已独立标定）；
    //   ③ 是「**场景判定**有多可信」（决策层置信度）。
    //   common 的 `SceneClassifier` 用 `ai.confidence >= 0.85f` 判断要不要让 AI 覆盖规则结果，
    //   这道门槛是**跨端契约** ⇒ ③ 必须与 Android 侧同口径，
    //   否则同一个场景在两端会走出完全不同的建议（iOS 永远不覆盖规则 / 或永远覆盖）。
    //   ⚠️ 这也是**唯一**允许与 Android 侧数值对齐的一组常量，且**不写进 Android 的代码**。

    /// 「合影」的判定置信度（人脸 ≥ 3）。
    static let groupConfidence: Float = 0.92
    /// 「人像」的判定置信度基准（有人脸且面积达标），实际值会随人脸面积上浮。
    static let portraitBaseConfidence: Float = 0.86
    /// 「人像」的判定置信度上限。
    static let portraitMaxConfidence: Float = 0.98
    /// 「人像」由**标签**（而非人脸证据）判定时的置信度。
    static let portraitLabelConfidence: Float = 0.74
    /// 「食物」的判定置信度。
    static let foodConfidence: Float = 0.90
    /// 「宠物」的判定置信度。
    static let petConfidence: Float = 0.88
    /// 「风景」的判定置信度。
    ///
    /// ★ Android 侧分三档（sky+nature 0.93 / nature 0.86 / sky 0.82），
    ///   而 Vision 的标签**不区分 sky 与 nature**（两者都归进风景族），
    ///   ⇒ iOS 侧只有一个风景出口。取 Android 的 nature-only 档（0.86）作为**保守值**，
    ///   待 CAL-2 真机标定后再调整。
    static let landscapeConfidence: Float = 0.86
    /// 「建筑」的判定置信度。
    static let architectureConfidence: Float = 0.82
    /// 「花草」的判定置信度。
    static let flowerConfidence: Float = 0.80
    /// 「未识别」的判定置信度（与 Android 侧 `decide()` 的兜底 0.30f 一致）。
    static let unknownConfidence: Float = 0.30

    // ── ④ 运行频率与数据量 ──

    /// 场景识别的**最高分析帧率**。
    ///
    /// ★ 硬上限 10fps：`VNClassifyImageRequest.perform(_:)` 是**阻塞调用**
    ///   （老机型 50–300ms），必须限流，且与 1Hz 的评分管线**解耦**
    ///   （分类结果只用于场景判定，不参与逐帧统计）。
    static let maximumFramesPerSecond: Int = 10

    /// 参与聚合的最大标签条数。
    ///
    /// ★ Vision 会返回上百条标识符（层级树上上下都有），
    ///   但 `results` 已按置信度降序 ⇒ 只看前 N 条即可，多余的既不改变结论也白花 CPU。
    static let maximumClassificationObservations: Int = 50

    // ── ⑤ 头部朝向（yaw）换算 ──

    /// Vision 的 `yaw`（**弧度**）→ common `AiVisionResult.headEulerY`（**度**）的符号。
    ///
    /// ★ 三个事实：
    ///   · Vision：`VNFaceObservation.yaw` 是 **弧度**（Apple 官方文档：
    ///     `The yaw angle of a face in radians`）。
    ///   · ML Kit：`Face.getHeadEulerAngleY()` 是**度**，正方向为
    ///     **脸转向画面的右侧**（Google 官方文档：`Positive euler y is when the face
    ///     turns toward the right side of the image that is being processed`）。
    ///   · Vision 的 yaw 正方向在 Apple 文档里描述为 **counterclockwise**，
    ///     与 ML Kit 的「朝画面右侧」**是否同向，需要真机确认**。
    ///
    /// ⚠️ **必须真机复核**（T22 · CAL-2）：如果对照时发现两端朝向相反，
    ///   **只改这一个常量**（改成 -1），不要去动算法。
    static let visionYawSign: Float = 1
}

// MARK: - 映射表

/// Vision 分类标识符 → common `AiScene` 的映射表。
///
/// ★ 当前是**空表**（尚未真机标定）—— 详见本文件开头的说明。
///   表结构、匹配规则、聚合算法、阈值都已就位；**只差真机采回来的数据**。
enum VisionSceneMap {

    /// common `AiScene` 的取值入口。
    ///
    /// ★★ **全工程只有这一处写 `AiScene.xxx`** —— 这是刻意收窄的，理由有二：
    ///   1. **单点事实来源**：Kotlin 的枚举导出到 Swift 是**一个 ObjC 类 + 一组类属性**
    ///      （不是 Swift 原生 enum），它的 Swift 命名由 Kotlin/Native 的 ObjC 导出规则决定。
    ///      万一真机编译时发现拼写与预期不同（`portrait` / `PORTRAIT`），
    ///      **只需要改这一个文件里的 8 行**，别处一行都不用动。
    ///   2. **让「用的是 common 的枚举」这件事一眼可见** ——
    ///      杜绝「Swift 侧另建一个枚举 + String 中转」这种将来要同步维护两张表的做法。
    enum Scenes {
        /// 人像。
        static let portrait = AiScene.portrait
        /// 合影。
        static let group = AiScene.group
        /// 风景。
        static let landscape = AiScene.landscape
        /// 建筑。
        static let architecture = AiScene.architecture
        /// 食物。
        static let food = AiScene.food
        /// 宠物。
        static let pet = AiScene.pet
        /// 花草。
        static let flower = AiScene.flower
        /// 未识别（兜底）。
        static let unknown = AiScene.unknown
    }

    /// 映射表里的一条规则：一个**标识符前缀** → 一个**场景** + 该族自己的**阈值**。
    ///
    /// ★ 为什么用「前缀」而不是「全等」：
    ///   Vision 词表是层级树（`animal` → `animal_mammal` → `animal_mammal_dog`），
    ///   同一族的具体词条会随系统版本增减。全等匹配一改版就整片失效，
    ///   前缀匹配则是「只增不改」。
    struct Entry {
        /// 标识符前缀（不含结尾分隔符）。例：`animal`。
        let prefix: String
        /// 命中后归入的场景（common 的 `AiScene`，不是 String）。
        let scene: AiScene
        /// 该族自己的最低置信度阈值。
        /// ★ `nil` = 用全局默认阈值（`VisionSceneThresholds.minimumClassificationConfidence`）；
        ///   给具体值 = 这一族更严（或更松），**每族独立标定**。
        let minimumConfidence: Float?

        /// 构造一条规则。
        /// - Parameters:
        ///   - prefix: 标识符前缀。
        ///   - scene: 命中后归入的场景。
        ///   - minimumConfidence: 该族自己的阈值；`nil` 表示用全局默认。
        init(prefix: String, scene: AiScene, minimumConfidence: Float? = nil) {
            self.prefix = prefix
            self.scene = scene
            self.minimumConfidence = minimumConfidence
        }
    }

    /// 一条 Vision 分类标签（`(identifier, confidence)` 对）。
    struct LabelObservation {
        /// Vision 的原始标识符（层级串，例：`animal_mammal_dog`）。
        let identifier: String
        /// 该标识符的置信度（0...1）。
        let confidence: Float

        /// 构造一条标签观测。
        /// - Parameters:
        ///   - identifier: Vision 原始标识符。
        ///   - confidence: 置信度（0...1）。
        init(identifier: String, confidence: Float) {
            self.identifier = identifier
            self.confidence = confidence
        }
    }

    /// 多标签聚合的结果。
    struct Aggregate {
        /// 聚合出来的场景。
        let scene: AiScene
        /// 该场景的置信度（取命中标签里的最高值）。
        let confidence: Float
        /// 贡献了这个场景的标识符（已去重）。
        let identifiers: [String]
    }

    /// ★★ **当前的映射表 —— 空表。**
    ///
    /// 回填方式（T22 · CAL-1，详见 `VisionCalibrationCollector`）：
    ///   1. 真机跑 `VisionCalibrationCollector`，把 8 类样张的 `(identifier, confidence)` 全量导出；
    ///   2. 人工把**真实出现过**的标识符按族归类（只保留出现过的，不做全集）；
    ///   3. 把族前缀写进本数组，形如：
    ///      `Entry(prefix: "animal", scene: AiScene.pet)`
    ///   4. 同时把该族的阈值按真机分布固化（写进 `Entry.minimumConfidence`，
    ///      或调整 `VisionSceneThresholds` 里的全局默认）；
    ///   5. 锁进测试（换 Vision revision 时只更新表，不改逻辑）。
    ///
    /// ★ 为什么「只保留真实出现过的标识符」：
    ///   写一个从未出现过的标识符，等于替 Vision 的行为做了承诺 ——
    ///   一旦系统版本变化，那条承诺就会变成一个**静默的错误分类**。
    static let table: [Entry] = []

    // MARK: - 匹配

    /// 找出标识符命中的规则（**最长前缀优先**）。
    ///
    /// ★ 边界的处理很关键：只有「完全相等」或「前缀 + `_` 分隔符」才算命中。
    ///   这样 `sky` 不会误命中 `skyline`。
    /// - Parameters:
    ///   - identifier: Vision 原始标识符。
    ///   - table: 要匹配的规则表（默认取本表的 `table`）。
    /// - Returns: 命中的规则在 `table` 中的下标；没命中返回 `nil`。
    static func matchIndex(identifier: String, table: [Entry] = VisionSceneMap.table) -> Int? {
        var bestIndex: Int?
        var bestLength = 0
        for (index, entry) in table.enumerated() {
            let prefix = entry.prefix
            guard !prefix.isEmpty else { continue }
            let isMatch = identifier == prefix || identifier.hasPrefix(prefix + "_")
            guard isMatch else { continue }
            // 最长前缀优先：更具体的族压过更宽泛的族。
            if prefix.count > bestLength {
                bestLength = prefix.count
                bestIndex = index
            }
        }
        return bestIndex
    }

    // MARK: - 多标签聚合

    /// 多标签聚合：遍历**全部**标签 → 命中映射表 → 取置信度最高者。
    ///
    /// ★ 这是 §10.2.2 规则 2 的实现，**不是** `results[0]`：
    ///   Vision 是多标签网络，`results[0]` 往往是最泛化的标签（`object` / `scene`），
    ///   取第一个会把「一只狗在草地上」判成「风景」。
    ///
    /// - Parameters:
    ///   - labels: Vision 返回的全部标签观测（已按置信度降序更好，但本函数不依赖顺序）。
    ///   - table: 映射表（默认取本表的 `table`）。
    ///   - minimumConfidence: 全局默认阈值（当表项没写自己的阈值时用）。
    /// - Returns: 聚合结果；**没有任何标签命中（或表为空）时返回 `nil`**
    ///   ⇒ 调用方据此走「归入 UNKNOWN」的安全路径。
    static func aggregate(
        _ labels: [LabelObservation],
        table: [Entry] = VisionSceneMap.table,
        minimumConfidence: Float = VisionSceneThresholds.minimumClassificationConfidence
    ) -> Aggregate? {
        // ★ 空表在这里就是合法的「什么都没命中」，不是异常 —— 直接返回 nil。
        guard !table.isEmpty, !labels.isEmpty else { return nil }

        // ★ 用 `hasBest` 标志位而不是「拿 Optional 去比」：
        //   Kotlin 枚举导出到 Swift 是一个 **ObjC 类**，`Optional<AiScene>` 之间的 `==`
        //   依赖 `NSObject` 的 `Equatable` 桥接（走 `isEqual:`）——
        //   行为依赖 Kotlin 导出实现，**不值得赌**。用标志位则完全绕开这个不确定性。
        //   非 Optional 的 `AiScene` 之间用 `==` 是安全的（AnyObject 的 `==`，走 `isEqual:`）。
        var hasBest = false
        var bestScene = Scenes.unknown
        var bestConfidence: Float = 0
        var bestIdentifiers: [String] = []

        for label in labels {
            guard let entryIndex = matchIndex(identifier: label.identifier, table: table) else {
                continue
            }
            let entry = table[entryIndex]
            // 该族自己的阈值优先；没写就用全局默认。
            let effectiveThreshold = entry.minimumConfidence ?? minimumConfidence
            guard label.confidence >= effectiveThreshold else { continue }

            if !hasBest || label.confidence > bestConfidence {
                hasBest = true
                bestScene = entry.scene
                bestConfidence = label.confidence
                bestIdentifiers = [label.identifier]
            } else if label.confidence == bestConfidence && entry.scene == bestScene {
                bestIdentifiers.append(label.identifier)
            }
        }

        guard hasBest else { return nil }
        return Aggregate(
            scene: bestScene,
            confidence: bestConfidence,
            identifiers: bestIdentifiers
        )
    }

    // MARK: - 中文标签

    /// Vision 标识符 → 中文标签的翻译表。
    ///
    /// ★ 当前是**空表**，与 `table` 一起由 T22 · CAL-1 回填。
    ///   Android 侧的 `CN` 映射表（`AiVision.kt`）是给 **ML Kit 英文标签**用的，
    ///   两端词表不同 ⇒ **不能共用**，也不该共用。
    private static let chineseLabels: [String: String] = [:]

    /// 把一个 Vision 标识符翻译成中文标签（未收录时原样返回）。
    ///
    /// ★ 与 Android 侧 `translate(tag) = CN[tag] ?: tag` 同策略：
    ///   词表未收录时宁可给原文，也不要给一个错的翻译。
    /// - Parameter identifier: Vision 原始标识符。
    /// - Returns: 中文标签，或原样返回的标识符。
    static func translate(_ identifier: String) -> String {
        chineseLabels[identifier] ?? identifier
    }

    /// 组装给用户看的标签列表（最多 6 条，与 Android 侧一致）。
    ///
    /// ★ 第一条固定是**场景自带的中文名**（`AiScene.label`，来自 common）——
    ///   它不是编造出来的，而是两端共用的那份「场景 → 展示文案」口径。
    ///   标定前（映射表为空）也能给用户一个正确的中文场景名。
    /// - Parameters:
    ///   - scene: 判定出的场景。
    ///   - identifiers: 贡献了这个场景的 Vision 标识符。
    /// - Returns: 最多 6 条中文标签。
    static func tagTexts(scene: AiScene, identifiers: [String]) -> [String] {
        var texts: [String] = []
        let sceneText = scene.label
        if !sceneText.isEmpty {
            texts.append(sceneText)
        }
        for identifier in identifiers {
            let text = translate(identifier)
            if text != sceneText && !texts.contains(text) {
                texts.append(text)
            }
            if texts.count >= 6 {
                break
            }
        }
        return Array(texts.prefix(6))
    }

    // MARK: - 场景判定置信度

    /// 场景判定置信度（决策层，不是识别层）。
    ///
    /// ★ 供 common `SceneClassifier` 的 `ai.confidence >= 0.85f` 门槛使用 ⇒ 必须跨端同口径。
    /// - Parameter scene: 判定出的场景。
    /// - Returns: 该场景的判定置信度。
    static func sceneDecisionConfidence(for scene: AiScene) -> Float {
        if scene == Scenes.group { return VisionSceneThresholds.groupConfidence }
        if scene == Scenes.portrait { return VisionSceneThresholds.portraitLabelConfidence }
        if scene == Scenes.landscape { return VisionSceneThresholds.landscapeConfidence }
        if scene == Scenes.architecture { return VisionSceneThresholds.architectureConfidence }
        if scene == Scenes.food { return VisionSceneThresholds.foodConfidence }
        if scene == Scenes.pet { return VisionSceneThresholds.petConfidence }
        if scene == Scenes.flower { return VisionSceneThresholds.flowerConfidence }
        return VisionSceneThresholds.unknownConfidence
    }
}
