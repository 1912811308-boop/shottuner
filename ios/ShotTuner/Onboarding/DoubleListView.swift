// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/Onboarding/DoubleListView.swift —— 「双清单」两页（个人信息收集清单 / 第三方共享清单）。
//
// ★ 为什么必须有这两页（上架硬门槛，不是锦上添花）：
//   工信部对 App 有「双清单」要求，部分商店审核时会检查应用内有没有这两个入口。
//   本应用虽然**一项信息都不收集**，仍要以「零收集」的形态把清单摆出来 ——
//   空白或「本 App 无」四个字都不算，必须有逐项说明。
//   见 `docs/RELEASE-PRD.md` 风险 6 与第 7 节第 5 项（阻塞级）。
//
// ★ 文案口径（一个字都不自己发明）：
//   正文逐字取自 `docs/RELEASE-PRD.md`：
//     · 个人信息收集清单 → §一「我们收集哪些信息」的 5 条 + 末段结论；
//     · 第三方共享清单   → §三「第三方 SDK 清单」的表格两条 + 补充说明 4 条。
//   这两段与 Android 关于页里的同两段**同源**（Android 侧在
//   `app/src/main/res/layout/activity_main.xml` 的 `llAbout` 里，也是从 PRD 抄的）。
//   ⇒ 两端说的必须是同一句话；任何一端单独润色，用户对比两台手机就会发现不一样。
//
// ★ 为什么每页都带一张「iOS 版说明」卡片（这不是改写政策，是交代平台差异）：
//   PRD 那两段是按 Android 版写的（INTERNET 权限、IMEI/OAID、ML Kit）。
//   iOS 版的事实不同：没有「网络权限」这个开关、不读 IDFA、没有 ML Kit。
//   如果照抄不解释，就是**在自己的隐私清单里写假话** —— 那比没有清单严重得多。
//   ⇒ 逐字正文（满足「与 PRD 口径一致」）+ 一张明确标注平台的差异卡片（满足「说得准」）。

import SwiftUI

// MARK: - 清单种类

/// 双清单的两页。
///
/// ★ 用枚举而不是两套文案各写一遍：两个页面除了内容之外完全同构（标题 + 结论 + 条目 +
///   说明卡片），共用一个 `View` 才能保证排版一致 —— 而「两页长得不一样」正是
///   这类合规页面最常见的减分项。
///
/// ★ `String` 原始值：`Identifiable` 需要稳定身份，`rawValue` 比自增 `UUID` 更稳。
enum DoubleListKind: String, Identifiable, CaseIterable {

    /// 个人信息收集清单。
    case personalInfoCollected

    /// 第三方共享清单。
    case thirdPartyShared

    var id: String { rawValue }

    /// 页面标题（与 PRD / 商店表单里的叫法一字不差）。
    var listTitle: String {
        switch self {
        case .personalInfoCollected:
            return "个人信息收集清单"
        case .thirdPartyShared:
            return "第三方共享清单"
        }
    }

    /// 结论行（页面上字号最大的那一句）。
    var headline: String {
        switch self {
        case .personalInfoCollected:
            return "本应用不收集任何个人信息。"
        case .thirdPartyShared:
            return "本应用不存在与任何第三方共享、转让、公开披露个人信息的情形，因为本应用不持有任何个人信息。"
        }
    }

    /// 结论行下面那句引导（逐字 PRD）。
    var leadIn: String {
        switch self {
        case .personalInfoCollected:
            return "具体而言，本应用："
        case .thirdPartyShared:
            return "本应用仅集成以下两个第三方 SDK，二者均为随安装包分发的端侧模型，在你的手机本地完成计算。"
        }
    }

    /// 逐条说明（个人信息收集清单用；第三方清单不显示这一块）。
    var bullets: [String] {
        switch self {
        case .personalInfoCollected:
            // 逐字：`docs/RELEASE-PRD.md` §一 第 1~5 条。
            return [
                "1. 不收集你的姓名、手机号、身份证号、邮箱、通讯录、短信、位置、"
                    + "设备标识符（IMEI / OAID / Android ID / MAC 地址）等任何可识别你身份的信息；",
                "2. 不收集你的照片、视频、音频；",
                "3. 不收集你的使用行为、点击记录、崩溃日志；",
                "4. 不建立任何用户账号，也没有登录功能；",
                "5. 不进行用户画像、个性化推荐或定向推送。",
            ]
        case .thirdPartyShared:
            return []
        }
    }

    /// 第三方 SDK 条目（第三方共享清单用；个人信息清单不显示这一块）。
    var sdkRows: [DoubleListSDK] {
        switch self {
        case .personalInfoCollected:
            return []
        case .thirdPartyShared:
            // 逐字：`docs/RELEASE-PRD.md` §三 表格的两行。
            return [
                DoubleListSDK(
                    id: "image-labeling",
                    name: "ML Kit 图像标注（image-labeling）",
                    provider: "Google LLC",
                    version: "17.0.9",
                    purpose: "在本机识别画面主体的类别（如人物、食物、建筑），用于推荐匹配的拍摄技法",
                    collectedPersonalInfo: "无",
                    transferOverNetwork: "否。模型随安装包分发，推理在本地完成"
                ),
                DoubleListSDK(
                    id: "face-detection",
                    name: "ML Kit 人脸检测（face-detection）",
                    provider: "Google LLC",
                    version: "16.1.6",
                    purpose: "在本机检测人脸的位置与朝向，用于构图建议与测光参考",
                    collectedPersonalInfo: "无",
                    transferOverNetwork: "否。同上"
                ),
            ]
        }
    }

    /// 逐字取自 PRD 的补充说明。
    ///
    /// - 个人信息收集清单：§一 末段（网络能力那段结论）；
    /// - 第三方共享清单：§三 补充说明 1~4。
    var notes: [String] {
        switch self {
        case .personalInfoCollected:
            return [
                "本应用未申请 INTERNET 网络访问权限，也未在代码中包含任何网络请求逻辑。"
                    + "这意味着本应用在操作系统层面不具备联网能力，从技术上无法将任何数据发送至外部服务器。",
            ]
        case .thirdPartyShared:
            return [
                "1. 上述 SDK 均为 Google ML Kit 的 bundled（离线）版本，模型文件打包在安装包内，"
                    + "运行时不依赖 Google Play 服务，不产生网络请求。",
                "2. 人脸检测的输入是相机预览帧或屏幕帧，输出是坐标与朝向数值，"
                    + "不生成人脸特征值、不做人脸比对、不做身份识别、不存储人脸图像，"
                    + "因此不构成《个人信息保护法》所称的敏感个人信息处理。",
                "3. 本应用未集成任何广告 SDK、统计分析 SDK、推送 SDK、社交分享 SDK、崩溃上报 SDK。",
                "4. 本应用不存在与任何第三方共享、转让、公开披露个人信息的情形，"
                    + "因为本应用不持有任何个人信息。",
            ]
        }
    }

    /// iOS 平台差异说明（**不是**对上面正文的修改，是交代它在 iOS 上怎么落地）。
    var iosNotePoints: [String] {
        switch self {
        case .personalInfoCollected:
            return [
                "「未申请 INTERNET 权限」在 iOS 上对应的是：本应用不含任何网络请求代码，"
                    + "也没有放宽 ATS（App Transport Security），在系统层面同样不具备联网能力。",
                "设备标识符：iOS 版不读取 IDFA / IDFV，也不使用广告归因或应用跟踪"
                    + "（未申请「允许跟踪」授权）。",
                "崩溃与诊断日志：iOS 版不集成任何崩溃上报或统计分析组件，也不会生成诊断文件。",
                "照片：只有你按下快门时，照片才会由系统写入你的相册（仅申请「添加照片」权限）；"
                    + "本应用不保留副本，也不读取相册里已有的照片。",
            ]
        case .thirdPartyShared:
            return [
                "iOS 版不集成任何第三方 SDK。画面主体识别改用随 iOS 提供的系统框架 Apple Vision"
                    + "（随系统分发，不属于第三方 SDK），同样在本机完成计算、不联网传输。",
                "上表两条 ML Kit 条目对应的是 Android 版。iOS 版的人脸检测同样只输出坐标与朝向数值，"
                    + "不生成人脸特征值、不做比对、不做身份识别、不存储人脸图像。",
                "iOS 版没有随 App 下载的模型文件，也没有模型更新通道 —— 端侧推理全部在本机完成。",
            ]
        }
    }
}

// MARK: - 第三方 SDK 条目

/// 一条第三方 SDK 的展示数据（本地值类型）。
///
/// ★ 字段名与 PRD 表格的列一一对应，渲染时不再换算：
///   多一层「格式化」就多一处口径走样的地方，而这张表是给审核和用户看的证据。
struct DoubleListSDK: Identifiable {

    /// 稳定身份（用 SDK 的内部名，不用自增下标）。
    let id: String

    /// SDK 名称。
    let name: String

    /// 提供方。
    let provider: String

    /// 版本。
    let version: String

    /// 使用目的。
    let purpose: String

    /// 收集的个人信息。
    let collectedPersonalInfo: String

    /// 是否联网传输。
    let transferOverNetwork: String
}

// MARK: - 清单页

/// 双清单的通用页面。
///
/// ★ 本视图**不套** `NavigationView`：它是由关于页 `NavigationLink` 推入的二级页面，
///   导航栏由外层提供（进入方式见 `AboutView`）。这与 `TechniqueDetailView` 同一个做法。
struct DoubleListView: View {

    /// 展示哪一页。
    let kind: DoubleListKind

    // MARK: 布局常量

    private enum Layout {
        /// 卡片圆角，与复盘卡 / 任务卡 / 录像方案页一致。
        static let cornerRadius: CGFloat = 12
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                headlineBlock

                if !kind.bullets.isEmpty {
                    bulletBlock
                }

                if !kind.sdkRows.isEmpty {
                    sdkBlock
                }

                if !kind.notes.isEmpty {
                    noteBlock
                }

                iosNoteBlock
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Color.black)
        .navigationTitle(kind.listTitle)
        .navigationBarTitleDisplayMode(.inline)
        // 强制深色：`Palette` 的文字色是给深底设计的浅色，浅色外观下会变成白字白底。
        .preferredColorScheme(.dark)
    }

    // MARK: 子视图

    /// 结论行 + 引导句。
    private var headlineBlock: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(kind.headline)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Palette.overlayText)
                .lineSpacing(4)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)

            Text(kind.leadIn)
                .font(.system(size: 12))
                .foregroundStyle(Palette.overlayTextSecondary)
                .lineSpacing(4)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    /// 逐条说明（个人信息收集清单的 5 条）。
    private var bulletBlock: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(kind.bullets, id: \.self) { bullet in
                Text(bullet)
                    .font(.system(size: 12))
                    .foregroundStyle(Palette.overlayTextSecondary)
                    .lineSpacing(4)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.overlayText.opacity(0.06))
        .clipShape(RoundedRectangle(cornerRadius: Layout.cornerRadius, style: .continuous))
    }

    /// 第三方 SDK 条目（每一条一张小卡）。
    private var sdkBlock: some View {
        VStack(alignment: .leading, spacing: 12) {
            ForEach(kind.sdkRows) { row in
                VStack(alignment: .leading, spacing: 6) {
                    Text(row.name)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Palette.overlayText)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)

                    fieldLine("提供方", row.provider)
                    fieldLine("版本", row.version)
                    fieldLine("使用目的", row.purpose)
                    fieldLine("收集的个人信息", row.collectedPersonalInfo)
                    fieldLine("是否联网传输", row.transferOverNetwork)
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Palette.overlayText.opacity(0.06))
                .clipShape(RoundedRectangle(cornerRadius: Layout.cornerRadius, style: .continuous))
            }
        }
    }

    /// 一行「字段名：值」。
    ///
    /// ★ 不用 `HStack` 左右分栏：`使用目的` / `是否联网传输` 两个值都是整句，
    ///   分栏之后在窄屏上会挤成竖排。一行到底、靠冒号分隔，读起来更像清单。
    private func fieldLine(_ label: String, _ value: String) -> some View {
        Text("\(label)：\(value)")
            .font(.system(size: 11))
            .foregroundStyle(Palette.overlayTextSecondary)
            .lineSpacing(3)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// 逐字补充说明。
    private var noteBlock: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(kind.notes, id: \.self) { note in
                Text(note)
                    .font(.system(size: 11))
                    .foregroundStyle(Palette.overlayTextSecondary)
                    .lineSpacing(3)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.overlayText.opacity(0.06))
        .clipShape(RoundedRectangle(cornerRadius: Layout.cornerRadius, style: .continuous))
    }

    /// iOS 平台差异说明。
    ///
    /// ★ 边框换成主色描边、标题写「iOS 版说明」，是为了让读者一眼看出
    ///   「上面是两份平台共用的口径，这一段是 iOS 的落地差异」——
    ///   混在一起读，会以为正文自相矛盾。
    private var iosNoteBlock: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("iOS 版说明")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(Palette.accent)

            ForEach(kind.iosNotePoints, id: \.self) { point in
                Text("· " + point)
                    .font(.system(size: 11))
                    .foregroundStyle(Palette.overlayTextSecondary)
                    .lineSpacing(3)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.overlayText.opacity(0.04))
        .clipShape(RoundedRectangle(cornerRadius: Layout.cornerRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: Layout.cornerRadius, style: .continuous)
                .stroke(Palette.accent.opacity(0.35), lineWidth: 1)
        )
    }
}
