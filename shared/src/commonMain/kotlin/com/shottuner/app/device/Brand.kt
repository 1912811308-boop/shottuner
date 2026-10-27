package com.shottuner.app.device

/**
 * 相机功能/模式的抽象。只列「用户真的需要被指路」的那些，
 * 不做厂商菜单的完整镜像——那是不可能维护的。
 */
enum class CameraMode(val label: String) {
    PHOTO_PRO("专业模式"),
    VIDEO_PRO("专业视频 / 电影模式"),
    PORTRAIT("人像"),
    NIGHT("夜景"),
    HDR("HDR"),
    SLOW_MO("慢动作"),
    TIME_LAPSE("延时摄影"),
    PANORAMA("全景"),
    MACRO("微距"),
    LONG_EXPOSURE("长曝光"),
    RAW("RAW / 原始格式"),
    ACTION("运动抓拍 / 追焦"),
    STABILIZE("视频防抖"),
    GRID("参考线"),
    BITRATE("码率设置"),
    ZOOM("镜头切换")
}

/**
 * 厂商识别。
 *
 * 为什么要分厂商：参数推荐全是「运行时探测」的，跨机型通用；
 * 但「这个功能在哪儿点」这件事完全取决于厂商 UI，只能按品牌给文案。
 * 两层分开，是本应用不依赖机型数据库也能覆盖所有机型的原因。
 */
enum class Brand(val label: String, val osHint: String) {
    XIAOMI("小米", "HyperOS / MIUI"),
    REDMI("红米", "HyperOS / MIUI"),
    HUAWEI("华为", "HarmonyOS / EMUI"),
    HONOR("荣耀", "MagicOS"),
    OPPO("OPPO", "ColorOS"),
    ONEPLUS("一加", "ColorOS / OxygenOS"),
    REALME("realme", "realme UI"),
    VIVO("vivo", "OriginOS / Funtouch"),
    IQOO("iQOO", "OriginOS"),
    SAMSUNG("三星", "One UI"),
    PIXEL("Pixel", "原生 Android"),
    MOTOROLA("摩托罗拉", "My UX"),
    NUBIA("努比亚 / 红魔", "MyOS / RedMagic OS"),
    MEIZU("魅族", "Flyme"),
    ASUS("华硕", "ZenUI"),
    SONY("索尼", "Xperia UI"),
    NOTHING("Nothing", "Nothing OS"),
    OTHER("其他", "Android");

    companion object {
        /**
         * 从 Build 信息判品牌。
         *
         * 顺序有讲究：子品牌必须放在父品牌之前判（iQOO 的 manufacturer 常是 vivo，
         * realme 的 manufacturer 有时是 OPPO）。用 brand 字段优先，model 做兜底。
         */
        fun identify(manufacturer: String?, brand: String?, model: String?): Brand {
            val m = (manufacturer ?: "").lowercase()
            val b = (brand ?: "").lowercase()
            val md = (model ?: "").lowercase()
            val key = "$b $m $md"

            if (key.contains("iqoo")) return IQOO
            if (key.contains("redmi")) return REDMI
            if (key.contains("oneplus") || b == "oneplus") return ONEPLUS
            if (key.contains("realme")) return REALME
            if (key.contains("honor")) return HONOR
            if (key.contains("nothing")) return NOTHING
            if (key.contains("nubia") || key.contains("redmagic") || key.contains("红魔")) return NUBIA

            return when {
                b == "xiaomi" || m == "xiaomi" -> XIAOMI
                b == "huawei" || m == "huawei" -> HUAWEI
                b == "oppo" || m == "oppo" -> OPPO
                b == "vivo" || m == "vivo" -> VIVO
                b == "samsung" || m == "samsung" -> SAMSUNG
                m == "google" || key.contains("pixel") -> PIXEL
                m == "motorola" || b == "motorola" -> MOTOROLA
                m == "meizu" || b == "meizu" -> MEIZU
                m == "asus" || b == "asus" -> ASUS
                b == "sony" || m == "sony" -> SONY
                else -> OTHER
            }
        }
    }
}

/**
 * 品牌路径库：告诉用户「这个按钮在你的手机上藏在哪」。
 *
 * 写作原则：给可执行的路径，不给功能名。说「顶部模式栏往左滑到底」，
 * 而不是「进入专业模式」——后者等于没说。
 * 并统一附一句「版本不同菜单名会变，找不到就在设置里搜关键词」，
 * 因为固件升级改名是常态，硬承诺一定对是骗人。
 */
object BrandGuide {

    const val DISCLAIMER = "菜单名随系统版本变化，找不到时在相机设置里搜关键词即可"

    fun hint(brand: Brand, mode: CameraMode): String = when (brand) {

        Brand.XIAOMI, Brand.REDMI -> when (mode) {
            CameraMode.PHOTO_PRO -> "相机顶部模式栏向左滑到底，「专业」"
            CameraMode.VIDEO_PRO -> "先切到「录像」，再点顶部的「专业」开关；拍 Log 要在设置里打开「Log 视频」"
            CameraMode.PORTRAIT -> "顶部模式栏的「人像」，点右侧光圈图标可调虚化强度"
            CameraMode.NIGHT -> "模式栏的「夜景」；2 秒以上会在屏幕下方显示秒数"
            CameraMode.HDR -> "顶部「HDR」（部分机型在设置里叫「自动 HDR」）"
            CameraMode.SLOW_MO -> "「更多」→「慢动作」，可切 120/240/960fps"
            CameraMode.TIME_LAPSE -> "「更多」→「延时摄影」，点右上角可改间隔"
            CameraMode.PANORAMA -> "「更多」→「全景」"
            CameraMode.MACRO -> "靠近主体自动触发微距，或「更多」→「微距」"
            CameraMode.LONG_EXPOSURE -> "「更多」→「长曝光」，有穿梭人群 / 行云流水 / 璀璨星空等预设"
            CameraMode.RAW -> "专业模式顶部「RAW」开关"
            CameraMode.ACTION -> "「更多」→「运动抓拍」；或长按快门连拍"
            CameraMode.STABILIZE -> "相机设置 →「视频防抖」，可选标准 / 超级防抖"
            CameraMode.GRID -> "相机设置 →「参考线」→ 九宫格"
            CameraMode.BITRATE -> "录像 → 专业 → 右上角设置，可改码率与编码"
            CameraMode.ZOOM -> "取景框上的「1x / 2x / 3.2x」是光学镜头，点数字切换，别用双指放大"
        }

        Brand.HUAWEI, Brand.HONOR -> when (mode) {
            CameraMode.PHOTO_PRO -> "「更多」→「专业」"
            CameraMode.VIDEO_PRO -> "「更多」→「电影」或「专业录像」；Log 在电影模式设置里"
            CameraMode.PORTRAIT -> "「人像」，右侧可调光圈与美颜"
            CameraMode.NIGHT -> "「夜景」"
            CameraMode.HDR -> "顶部「HDR」"
            CameraMode.SLOW_MO -> "「更多」→「慢动作」"
            CameraMode.TIME_LAPSE -> "「更多」→「延时摄影」"
            CameraMode.PANORAMA -> "「更多」→「全景」"
            CameraMode.MACRO -> "「更多」→「超级微距」"
            CameraMode.LONG_EXPOSURE -> "「更多」→「流光快门」，有车水马龙 / 光绘涂鸦等"
            CameraMode.RAW -> "专业模式设置里开「RAW 格式」"
            CameraMode.ACTION -> "「更多」→「抓拍」，或长按快门"
            CameraMode.STABILIZE -> "相机设置 →「视频防抖」"
            CameraMode.GRID -> "相机设置 →「参考线」"
            CameraMode.BITRATE -> "专业录像 → 设置，可调码率"
            CameraMode.ZOOM -> "取景框上的镜头数字，优先点光学焦段"
        }

        Brand.OPPO, Brand.ONEPLUS, Brand.REALME -> when (mode) {
            CameraMode.PHOTO_PRO -> "「更多」→「专业」"
            CameraMode.VIDEO_PRO -> "「更多」→「电影」；Log 在电影模式右上角设置"
            CameraMode.PORTRAIT -> "「人像」，可调虚化与美颜"
            CameraMode.NIGHT -> "「夜景」"
            CameraMode.HDR -> "顶部「HDR」"
            CameraMode.SLOW_MO -> "「更多」→「慢动作」，可切 120/480fps"
            CameraMode.TIME_LAPSE -> "「更多」→「延时摄影」"
            CameraMode.PANORAMA -> "「更多」→「全景」"
            CameraMode.MACRO -> "「更多」→「微距」"
            CameraMode.LONG_EXPOSURE -> "「更多」→「长曝光」"
            CameraMode.RAW -> "专业模式里开「RAW」"
            CameraMode.ACTION -> "「更多」→「运动」；或长按快门连拍"
            CameraMode.STABILIZE -> "相机设置 →「视频防抖」"
            CameraMode.GRID -> "相机设置 →「网格」"
            CameraMode.BITRATE -> "电影模式 → 设置，可调码率"
            CameraMode.ZOOM -> "取景框上的焦段按钮，优先光学镜头"
        }

        Brand.VIVO, Brand.IQOO -> when (mode) {
            CameraMode.PHOTO_PRO -> "「更多」→「专业」"
            CameraMode.VIDEO_PRO -> "「更多」→「专业录像」或「电影人像」"
            CameraMode.PORTRAIT -> "「人像」，右侧可调光圈"
            CameraMode.NIGHT -> "「夜景」"
            CameraMode.HDR -> "顶部「HDR」"
            CameraMode.SLOW_MO -> "「更多」→「慢动作」"
            CameraMode.TIME_LAPSE -> "「更多」→「延时摄影」"
            CameraMode.PANORAMA -> "「更多」→「全景」"
            CameraMode.MACRO -> "「更多」→「微距」"
            CameraMode.LONG_EXPOSURE -> "「更多」→「长曝光」"
            CameraMode.RAW -> "专业模式设置里开「RAW」"
            CameraMode.ACTION -> "「更多」→「运动抓拍」"
            CameraMode.STABILIZE -> "相机设置 →「视频防抖」"
            CameraMode.GRID -> "相机设置 →「构图线」"
            CameraMode.BITRATE -> "专业录像 → 设置"
            CameraMode.ZOOM -> "取景框焦段按钮，优先光学镜头"
        }

        Brand.SAMSUNG -> when (mode) {
            CameraMode.PHOTO_PRO -> "「更多」→「专业」"
            CameraMode.VIDEO_PRO -> "「更多」→「专业视频」；Log 需在专业视频设置里开"
            CameraMode.PORTRAIT -> "「人像」，可调虚化"
            CameraMode.NIGHT -> "「夜间模式」"
            CameraMode.HDR -> "顶部「HDR」"
            CameraMode.SLOW_MO -> "「更多」→「慢动作」"
            CameraMode.TIME_LAPSE -> "「更多」→「延时摄影」"
            CameraMode.PANORAMA -> "「更多」→「全景」"
            CameraMode.MACRO -> "靠近主体自动切微距镜头"
            CameraMode.LONG_EXPOSURE -> "用「专业」模式把快门拨慢，配三脚架"
            CameraMode.RAW -> "用「Expert RAW」独立应用"
            CameraMode.ACTION -> "长按快门连拍"
            CameraMode.STABILIZE -> "相机设置 →「视频稳定」"
            CameraMode.GRID -> "相机设置 →「网格线」"
            CameraMode.BITRATE -> "专业视频 → 设置，可改码率与比特率"
            CameraMode.ZOOM -> "取景框焦段按钮，优先光学镜头"
        }

        Brand.PIXEL -> when (mode) {
            CameraMode.PHOTO_PRO -> "原生相机没有专业模式，只有取景框上的「曝光补偿」和「白平衡」两个滑杆"
            CameraMode.VIDEO_PRO -> "没有专业视频；录像只能改分辨率和帧率，无手动曝光"
            CameraMode.PORTRAIT -> "「人像」，可调背景虚化强度"
            CameraMode.NIGHT -> "「夜景」，会自动多帧合成"
            CameraMode.HDR -> "HDR 默认常开，无需手动切换"
            CameraMode.SLOW_MO -> "「慢动作」"
            CameraMode.TIME_LAPSE -> "「延时摄影」"
            CameraMode.PANORAMA -> "「全景」"
            CameraMode.MACRO -> "靠近主体自动切超广角微距"
            CameraMode.LONG_EXPOSURE -> "没有长曝光；用「动态照片」或第三方应用"
            CameraMode.RAW -> "设置 → 高级 →「原始格式」"
            CameraMode.ACTION -> "长按快门连拍"
            CameraMode.STABILIZE -> "设置 →「视频稳定」"
            CameraMode.GRID -> "设置 →「网格类型」"
            CameraMode.BITRATE -> "不可调"
            CameraMode.ZOOM -> "取景框上的焦段，超过光学范围就是裁切"
        }

        else -> when (mode) {
            CameraMode.PHOTO_PRO -> "在相机的「更多」或模式列表里找「专业 / Pro」"
            CameraMode.VIDEO_PRO -> "录像模式下找「专业 / 电影 / Pro video」"
            CameraMode.PORTRAIT -> "模式列表里的「人像」"
            CameraMode.NIGHT -> "模式列表里的「夜景」"
            CameraMode.HDR -> "相机设置或顶部 HDR 开关"
            CameraMode.SLOW_MO -> "「更多」里的「慢动作」"
            CameraMode.TIME_LAPSE -> "「更多」里的「延时摄影」"
            CameraMode.PANORAMA -> "「更多」里的「全景」"
            CameraMode.MACRO -> "「更多」里的「微距」"
            CameraMode.LONG_EXPOSURE -> "「更多」里的「长曝光」"
            CameraMode.RAW -> "专业模式设置里开 RAW"
            CameraMode.ACTION -> "长按快门连拍，或运动模式"
            CameraMode.STABILIZE -> "相机设置里的 video stabilization"
            CameraMode.GRID -> "相机设置里的网格线 / 参考线"
            CameraMode.BITRATE -> "专业视频设置里看是否有码率选项"
            CameraMode.ZOOM -> "取景框上的焦段按钮，优先光学镜头"
        }
    }

    /** 该品牌是否有「真正的专业视频（可调快门）」——影响录像建议能不能照抄 */
    fun hasManualVideo(brand: Brand): Boolean = when (brand) {
        Brand.PIXEL, Brand.OTHER, Brand.MOTOROLA -> false
        else -> true
    }
}
