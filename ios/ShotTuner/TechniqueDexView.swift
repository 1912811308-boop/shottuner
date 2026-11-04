// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/TechniqueDexView.swift —— 技法图鉴（iOS 侧）。
//
// ★ 为什么这个页面能「今晚」就上线：
//   83 条拍照技巧**早已在 commonMain**（PhotographyBook.all），Android 的技法图鉴
//   一直读的是它。iOS 侧缺的从来不是内容，只是一个能显示它的界面 +
//   一条把 List<Technique> 拍平成标量的跨语言门面（TechniqueDexBridge）。
//   ⇒ 本文件只做渲染，不写任何业务逻辑，也不复制任何技巧文案。
//
// ★ 跨语言规则（见 SharedKernelBridge.swift 文件头）：
//   Kotlin `object` → Swift `Xxx.shared`；Int → Int32；边界只传标量。
//
// ★ 口径铁律：只说「参考分」，不说「正确/错误」「专业评测」；参数用「照拨」「建议值」。
//   页面上的文案全部来自技巧库本身，不改内容、不加评价。

import SwiftUI
import Shared

/// 一条技法在列表里的展示数据（本地值类型，避免 body 重算时反复跨语言）。
private struct TechniqueRow: Identifiable {
    let id: Int
    let title: String
    let category: String
    let oneLiner: String
}

/// 技法图鉴：与 Android 侧同源的 83 条拍照技巧。
struct TechniqueDexView: View {

    private let rows: [TechniqueRow]

    init() {
        // ★ 一次性读完，之后 body 只碰本地数组。
        //   Kotlin 的 Int 映射到 Swift 是 Int32，所以要显式转换。
        let total = Int(TechniqueDexBridge.shared.count())
        var built: [TechniqueRow] = []
        built.reserveCapacity(total)
        for i in 0..<total {
            let index = Int32(i)
            built.append(
                TechniqueRow(
                    id: i,
                    title: TechniqueDexBridge.shared.titleAt(index: index),
                    category: TechniqueDexBridge.shared.categoryLabelAt(index: index),
                    oneLiner: TechniqueDexBridge.shared.oneLinerAt(index: index)
                )
            )
        }
        rows = built
    }

    var body: some View {
        // 用 NavigationView（iOS 13+）而不是 NavigationStack（iOS 16+），
        // 避免在低部署目标上编译不过。
        NavigationView {
            List(rows) { row in
                NavigationLink(destination: TechniqueDetailView(index: Int32(row.id))) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(row.title)
                            .font(.subheadline.weight(.semibold))
                            .lineLimit(2)
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            Text(row.category)
                                .font(.caption2)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(Color.secondary.opacity(0.15))
                                .clipShape(Capsule())
                            Text(row.oneLiner)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                    }
                    .padding(.vertical, 2)
                }
            }
            .navigationTitle("技法图鉴")
            .navigationBarTitleDisplayMode(.inline)
            .overlay(alignment: .bottom) {
                Text("共 \(rows.count) 条 · 与 Android 版同源，照片不上传")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .padding(.bottom, 6)
            }
        }
    }
}

/// 单条技法的完整详情。
///
/// 详情文本由 **Kotlin 侧**拼好（TechniqueDexBridge.detailAt），
/// Swift 只负责显示 —— 这样两端的排版口径天然一致，也不用把
/// `steps` / `params` / `mistakes` 这些 List 逐个搬过语言边界。
private struct TechniqueDetailView: View {
    let index: Int32

    var body: some View {
        ScrollView {
            Text(TechniqueDexBridge.shared.detailAt(index: index))
                .font(.body)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(20)
        }
        .navigationTitle("技法详情")
        .navigationBarTitleDisplayMode(.inline)
    }
}
