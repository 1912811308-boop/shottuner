package com.shottuner.app.knowledge

import com.shottuner.app.model.SceneType

/**
 * 摄影技法库。
 *
 * 这里只放纯文案和匹配条件，不读硬件、不碰界面。条目的顺序不重要，
 * 真正决定推荐顺序的是 trigger 里的场景、数值条件和 weight。
 *
 * ## v1.5 拆分说明
 * 1.4 及以前这里只有一个 [all]。1.5 新增的 44 条放在独立文件
 * [TechniqueBookV15] 里（避免动这个 800 行的大文件），但**对外仍然只有
 * [all] 一个入口**：它等于 [legacy] + [TechniqueBookV15.list]。
 *
 * 这样 `allCategoriesCovered` / `textFieldsAreReasonable` 这类遍历全库的
 * 体检用例**一行都不用改**就能覆盖到新条目 —— 它们要的就是「全部」，
 * 而不是「某个文件里的那部分」。
 *
 * 原来的 39 条一字未动，只是从 `all` 改名成 [legacy]。
 */
object PhotographyBook {

    /** v1.4 及以前的 39 条。内容一行未改，仅供"与新增条目比对"和兼容老测试。 */
    val legacy: List<Technique> = listOf(
        // ---------------- 人像 ----------------
        Technique(
            id = "portrait_backlit_face_first",
            title = "逆光人像：先保脸，别保天",
            category = TechCategory.PORTRAIT,
            oneLiner = "脸亮比天亮重要",
            whenTo = "太阳在人物背后时，天空很亮、人脸发黑时用。先保证脸能看清，再决定天空要不要过曝。",
            steps = listOf(
                "点一下人脸，让测光以脸为准，别对着天空点。",
                "曝光补偿往加号推 0.7 到 1.5 档，直到脸从灰变亮。",
                "如果天空全白，把机位往侧面挪半步，让太阳偏出画面。",
                "拍完放大看眼睛和鼻尖，别只看整体亮度。"
            ),
            params = listOf(
                "曝光补偿" to "+0.7~+1.5EV",
                "测光" to "点测人脸",
                "机身" to "开 HDR 或人像模式"
            ),
            why = "逆光时天空亮度比人脸高好几档，相机按全画面平均测光，会把脸压成剪影。对人脸测光并加曝光，是让感光元件先保住脸的中间调；天空一旦超出动态范围，宁可让边缘过曝，也别让人脸欠曝。",
            mistakes = listOf(
                "对着天空测光，结果人脸全黑",
                "为了救天空把曝光压得太低，脸脏得没法看",
                "站在太阳正后方，镜头吃光起雾"
            ),
            check = "放大看人脸，眼睛里有高光、鼻翼还有细节；天空过曝可以接受。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.BACKLIT),
                minHighlightClip = 0.08f,
                minSkinRatio = 0.08f,
                weight = 30
            )
        ),
        Technique(
            id = "portrait_window_45",
            title = "窗光侧 45 度：脸上有过渡",
            category = TechCategory.PORTRAIT,
            oneLiner = "侧一点，脸才有立体感",
            whenTo = "在室内靠窗拍人像，光线从一侧进来时用。让脸和窗户成大约 45 度，不要正对或完全背对窗户。",
            steps = listOf(
                "让被拍的人侧对窗户，鼻尖朝窗外偏一点。",
                "你站在窗户侧面，别堵住光，先看脸上有没有明暗交界。",
                "如果半边脸太黑，用白墙或浅色衣服在暗侧补一点反光。"
            ),
            params = listOf(
                "位置" to "窗边 1 米内",
                "角度" to "脸与窗 45 度",
                "补光" to "白墙/浅色衣物"
            ),
            why = "正面平光会把鼻梁、颧骨和下颌的起伏压平，脸看起来像一张纸。侧 45 度让光斜着扫过皮肤，亮面和暗面之间出现连续过渡，大脑才能从明暗变化里读出三维形状。",
            mistakes = listOf(
                "让人正对窗户，脸平得没有鼻梁",
                "自己站在窗前挡住光，人物脸上出现你的影子"
            ),
            check = "看鼻梁两侧，一侧亮一侧有淡淡阴影，过渡不是一条硬边就对了。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.PORTRAIT, SceneType.INDOOR),
                minSkinRatio = 0.04f,
                maxLuma = 0.8f,
                weight = 20
            )
        ),
        Technique(
            id = "portrait_eyes_thirds",
            title = "眼睛放上三分线，别放正中",
            category = TechCategory.PORTRAIT,
            oneLiner = "先放眼睛，再管身体",
            whenTo = "拍半身或特写、人物眼睛清楚时用。先打开相机网格，把靠上的那只眼睛放在上三分线附近。",
            steps = listOf(
                "打开相机网格，找到横向第二条线。",
                "把人物眼睛移到这条线附近，头顶留一点空间。",
                "身体可以出画，眼睛别贴着边缘。"
            ),
            params = listOf(
                "网格" to "开",
                "眼睛位置" to "上三分线",
                "头顶留白" to "约 1/5 画面"
            ),
            why = "人看照片会先找眼睛。眼睛落在正中时，视线没有去处，画面容易呆；放到上三分线，眼睛和画面边缘之间留下呼吸空间，视线会顺着留白方向延伸，人物看起来更放松。",
            mistakes = listOf(
                "把头顶切掉，眼睛却还在画面中间",
                "眼睛贴边，人物像要走出画面"
            ),
            check = "缩小看整张图，第一眼落在眼睛上，头顶留白不憋屈。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.PORTRAIT),
                minSkinRatio = 0.04f,
                weight = 18
            )
        ),
        Technique(
            id = "portrait_low_angle_full",
            title = "低机位拍全身：腿长不靠修",
            category = TechCategory.PORTRAIT,
            oneLiner = "蹲下来，从腰的高度拍",
            whenTo = "拍全身、想让人显高时用。把手机降到人物腰部以下，镜头略微向上仰，别直接贴地仰拍。",
            steps = listOf(
                "蹲下或半跪，把手机放到人物腰腹高度。",
                "镜头稍微上仰，让人物头顶接近画面上沿。",
                "让人物一只脚往前伸，重心放在后脚。"
            ),
            params = listOf(
                "机位" to "腰腹高度",
                "仰角" to "5~10 度",
                "焦距" to "主摄 1 倍"
            ),
            why = "低机位缩短了镜头到腿的距离，腿部在画面里占的像素更多；同时向上仰拍让垂直线产生透视收缩，腿被拉长、头变小。贴地仰拍角度太猛，会把下巴和鼻孔放大，所以只抬一点就够。",
            mistakes = listOf(
                "整个人趴在地上仰拍，脸被拍成大下巴",
                "机位低但镜头还往下压，腿照样短"
            ),
            check = "看脚到腰的比例，腿占画面下半部，脸和鼻子没有明显变形。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.PORTRAIT),
                minSkinRatio = 0.04f,
                weight = 14
            )
        ),
        Technique(
            id = "portrait_background_layers",
            title = "背景要远、要暗、要乱得有序",
            category = TechCategory.PORTRAIT,
            oneLiner = "退一步，让背景离人远点",
            whenTo = "人像背景有树、招牌、路人时用。让人物离背景至少两三米远，再找一个比人脸更暗的角落。",
            steps = listOf(
                "让被拍的人往前走两三米，离开身后的墙和树。",
                "你跟着退，保持人物大小不变，背景自然被拉开。",
                "转半圈找暗背景，别让亮招牌贴在头边。"
            ),
            params = listOf(
                "人物距背景" to "> 2 米",
                "背景亮度" to "比人脸暗",
                "镜头" to "人像/2 倍"
            ),
            why = "背景离得远，景深之外的模糊半径变大，杂乱细节被抹成色块；背景比人脸暗，人物轮廓靠亮度差跳出来。亮招牌贴近头顶会抢视线，暗背景则把注意力推回脸上。",
            mistakes = listOf(
                "贴着白墙拍，人和墙糊成一片",
                "背景里有一根电线杆正好插在头上"
            ),
            check = "眯眼看，人物是画面里最亮的一块，背景没有抢眼的亮点。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.PORTRAIT),
                minSkinRatio = 0.04f,
                minEdgeDensity = 0.1f,
                weight = 16
            )
        ),
        Technique(
            id = "portrait_wide_face",
            title = "广角别怼脸，退两步或切 2 倍",
            category = TechCategory.PORTRAIT,
            oneLiner = "手机主摄拍脸会变宽",
            whenTo = "用主摄近距离拍人脸、发现鼻子大下巴宽时用。先退后一步，再切到 2 倍，别继续往前凑。",
            steps = listOf(
                "往后退半步到一步，让人脸别占满画面。",
                "切到 2 倍或人像镜头，再重新构图。",
                "如果只能靠近，让人侧脸，别正对镜头。"
            ),
            params = listOf(
                "焦距" to "2 倍/人像镜头",
                "距离" to "1.5 米以上",
                "角度" to "略侧脸"
            ),
            why = "手机主摄等效约 23mm，属于广角。离脸太近时，鼻子到镜头的距离比耳朵近很多，近大远小的透视被放大，鼻子和额头会鼓出来。退远或换更长焦距，等于把拍摄距离拉平，五官比例才回到正常。",
            mistakes = listOf(
                "用主摄贴脸自拍，还怪镜头畸变",
                "切了 2 倍但人还站在半米内，透视没变"
            ),
            check = "看鼻翼和脸宽，比例接近肉眼，脸部边缘没有明显拉伸。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.PORTRAIT),
                minSkinRatio = 0.1f,
                weight = 22
            )
        ),
        Technique(
            id = "portrait_hands_busy",
            title = "手要有事做，别让胳膊垂着",
            category = TechCategory.PORTRAIT,
            oneLiner = "给手找个动作",
            whenTo = "拍站姿人像、人物不知道手往哪放时用。让手拿东西、扶帽檐、插兜或整理头发，别让两条胳膊直直垂下。",
            steps = listOf(
                "让被拍的人手里拿杯水、书或手机。",
                "另一只手插兜、扶衣领，或者轻轻搭在腰间。",
                "按快门前让他做一个慢动作，别喊一二三站好。"
            ),
            params = listOf(
                "手部" to "持物/插兜",
                "姿态" to "一侧肩略低",
                "连拍" to "开"
            ),
            why = "手臂自然下垂时，肘关节和手腕形成两条平行直线，人物轮廓像一根柱子，画面没有节奏。给手一个动作会打破直线，肘部弯曲产生三角形，身体重心也跟着变化，站姿看起来才有生活感。",
            mistakes = listOf(
                "让两个人同时比剪刀手，画面更僵",
                "手插兜插得太深，肩膀耸起来"
            ),
            check = "看手肘有没有弯曲，人物轮廓不是一条直筒，就算过关。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.PORTRAIT),
                minSkinRatio = 0.04f,
                weight = 12
            )
        ),

        // ---------------- 风光 ----------------
        Technique(
            id = "landscape_golden_blue_hour",
            title = "黄金时刻与蓝调时刻，光比最听话",
            category = TechCategory.LANDSCAPE,
            oneLiner = "日出后日落前一小时",
            whenTo = "拍风光想少后期时用。日出后一小时和日落前一小时，太阳低、光软；日落后二十到四十分钟是蓝调时刻。",
            steps = listOf(
                "提前查好日落时间，提前四十分钟到机位。",
                "先拍顺光和侧光，太阳落到地平线后再转拍蓝调。",
                "把相机架稳，蓝调时刻曝光时间会变长。"
            ),
            params = listOf(
                "时间" to "日出后/日落前 1 小时",
                "白平衡" to "日光/阴天",
                "ISO" to "100 起"
            ),
            why = "太阳低时，光线穿过更厚的大气层，短波蓝光被散射掉，留下长波的红橙光；同时直射光变弱，天空和地面的亮度差缩小，传感器不用在死黑和死白之间二选一。蓝调时刻没有直射光，天空是均匀的冷色光源，颜色更干净。",
            mistakes = listOf(
                "太阳刚落下就收机器，错过蓝调",
                "正午顶光拍风光，阴影硬、天空白"
            ),
            check = "看天空和地面，亮部没全白、暗部还有细节，颜色不发灰。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.LANDSCAPE),
                minDynamicRange = 0.45f,
                minSaturation = 0.35f,
                weight = 24
            )
        ),
        Technique(
            id = "landscape_three_layers",
            title = "前景、中景、远景，三层才不空",
            category = TechCategory.LANDSCAPE,
            oneLiner = "找块石头放前面",
            whenTo = "拍大场景觉得画面空、没有纵深时用。把近处的石头、草、栏杆放进取景框下沿，中间放主体，远处留山或天。",
            steps = listOf(
                "蹲低，把一块石头或草丛放在画面下三分之一。",
                "让中景的树、房子或人落在画面中部。",
                "远处留山脊、云或天际线，别让三层叠在一起。"
            ),
            params = listOf(
                "前景占比" to "下 1/3",
                "机位" to "低机位",
                "光圈" to "小光圈/主摄"
            ),
            why = "照片是平面的，纵深靠遮挡和大小差建立。前景压住画面下沿，中景提供主体，远景给出方向；三层之间的重叠让大脑判断出前后距离，画面从一张墙变成一条路。",
            mistakes = listOf(
                "前景太大挡住主体，只剩一块石头",
                "三层都在同一条水平线上，还是平的"
            ),
            check = "眯眼看，能分出近、中、远三个亮度层次，主体没被前景吃掉。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.LANDSCAPE),
                minEdgeDensity = 0.22f,
                weight = 18
            )
        ),
        Technique(
            id = "landscape_leading_lines",
            title = "引导线：让视线自己走进画面",
            category = TechCategory.LANDSCAPE,
            oneLiner = "顺着路和栏杆拍",
            whenTo = "画面里有道路、栏杆、河流或走廊时用。让线条从画面下角进入，往主体或远处汇聚，别让线横着切断画面。",
            steps = listOf(
                "找到一条从近处延伸向远处的线，站到线的起点附近。",
                "把手机放低，让线从画面左下或右下角进入。",
                "线的尽头放一个主体，别让线指向画面外。"
            ),
            params = listOf(
                "机位" to "线起点附近",
                "线条" to "对角线进入",
                "焦点" to "线尽头主体"
            ),
            why = "人眼会沿着连续的边缘移动。道路、栏杆这类透视线在画面里形成由宽到窄的收敛，视觉重量被一路带向消失点；主体放在消失点附近，视线自然停在它身上，而不是在画面里乱逛。",
            mistakes = listOf(
                "把路放在画面正中，线把画面劈成两半",
                "引导线尽头什么都没有，视线扑空"
            ),
            check = "看画面，视线会从下角顺着线走到远处主体，中途没有被切断。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.LANDSCAPE),
                minEdgeDensity = 0.28f,
                weight = 20
            )
        ),
        Technique(
            id = "landscape_horizon_thirds",
            title = "地平线别压中线，留天或留地",
            category = TechCategory.LANDSCAPE,
            oneLiner = "要么多天，要么多地",
            whenTo = "拍海、草原、城市天际线时用。先决定天空好看还是地面好看，再把地平线放到上三分线或下三分线。",
            steps = listOf(
                "先抬头看天，再看地，选信息更多的一边。",
                "地平线放在上三分线，给地面留三分之二；反过来给天空留三分之二。",
                "把手机端平，别让地平线歪着。"
            ),
            params = listOf(
                "地平线" to "上/下三分线",
                "水平" to "开网格校准",
                "焦距" to "主摄或长焦"
            ),
            why = "地平线压在正中会把画面切成两个等大的方块，眼睛找不到主次。放到三分线，一边占三分之二成为主角，另一边当陪衬，画面有了明确倾向；同时避免等分带来的呆板。",
            mistakes = listOf(
                "地平线歪两度，整张图都像要倒",
                "地平线穿头，人物头顶长出一条线"
            ),
            check = "看地平线是否平直，天空和地面有明显主次，不觉得画面被切成两半。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.LANDSCAPE),
                minEdgeDensity = 0.24f,
                weight = 16
            )
        ),
        Technique(
            id = "landscape_polarizer",
            title = "偏振镜压反光，别让天空死白",
            category = TechCategory.LANDSCAPE,
            oneLiner = "转一转，反光会消失",
            whenTo = "拍水面、玻璃、树叶或蓝天时用。装上偏振镜后慢慢旋转，直到反光变弱、天空变深，别转到颜色发假。",
            steps = listOf(
                "把偏振镜拧到镜头上，先转到反光最强的位置。",
                "慢慢旋转，观察水面或玻璃上的反光变化。",
                "找到反光最少、天空颜色最自然的位置停下。"
            ),
            params = listOf(
                "附件" to "偏振镜",
                "角度" to "与太阳约 90 度",
                "旋转" to "边看边转"
            ),
            why = "水面和玻璃反射的是偏振光，偏振镜只让某一方向的振动通过，把反射光挡掉，底下的颜色和纹理才露出来。天空的蓝也带偏振成分，所以压暗天空要适度，转过头会出现不均匀的深色块。",
            mistakes = listOf(
                "对着太阳方向转偏振镜，几乎没效果",
                "转得太多，天空一半深一半浅"
            ),
            check = "看水面能见到底部，玻璃后的人或物清楚，天空颜色均匀不发黑。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.LANDSCAPE),
                minSaturation = 0.35f,
                weight = 14
            )
        ),
        Technique(
            id = "landscape_long_exposure_water",
            title = "长曝流水：把水拍成绸缎",
            category = TechCategory.LANDSCAPE,
            oneLiner = "架稳，曝光两秒以上",
            whenTo = "拍瀑布、溪流、海浪，想让水变柔时用。必须有三脚架或稳定支撑，曝光从 1 秒起，常见 2 到 8 秒。",
            steps = listOf(
                "把手机固定在三脚架上，关掉防抖或使用稳定模式。",
                "把快门拉到 1 秒以上，ISO 降到最低。",
                "光线太亮就加 ND 减光镜，别靠收小光圈硬压。"
            ),
            params = listOf(
                "快门" to "2~8 秒",
                "ISO" to "最低",
                "附件" to "三脚架 + ND"
            ),
            why = "水在流动，长曝光把每个像素在几秒内收到的水流轨迹叠加起来，随机的水花被平均成连续的雾状；静止的石头没有位移，仍然保持清晰。时间越长，水的丝滑感越强，但太长发白，需要看水流速度调。",
            mistakes = listOf(
                "手持长曝，水流和石头一起糊",
                "白天不加减光镜，快门拉不到 1 秒"
            ),
            check = "放大看石头边缘还是清楚的，水流连续没有断开的颗粒，就算成功。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.LANDSCAPE),
                requiresLongExposure = true,
                minEdgeDensity = 0.2f,
                weight = 26
            )
        ),

        // ---------------- 夜景 ----------------
        Technique(
            id = "night_handheld_static",
            title = "手持夜景：静止 3 秒再按",
            category = TechCategory.NIGHT,
            oneLiner = "屏住呼吸，别边走边拍",
            whenTo = "夜晚没有三脚架、想用手机直接拍街景时用。找墙、栏杆或桌面撑住手，按下快门后保持静止至少三秒。",
            steps = listOf(
                "找一面墙或栏杆，把肘部靠上去。",
                "双脚分开站稳，手机贴近身体，别悬空。",
                "按下快门后数三秒再动，等多帧合成结束。"
            ),
            params = listOf(
                "持机" to "靠墙/栏杆",
                "静止" to "3 秒以上",
                "模式" to "夜景/自动"
            ),
            why = "手机夜景靠连拍多帧再对齐合成，手持时每一帧都有轻微位移，算法要把它们叠在一起。曝光期间一动，帧与帧对不齐，边缘就出现重影；静止三秒能让合成吃到足够多的干净帧，噪点才压得下去。",
            mistakes = listOf(
                "按完快门马上放下手机，照片糊成一片",
                "边走边拍，路灯全拖成线"
            ),
            check = "放大看路灯边缘和招牌字，线条清楚没有重影，暗部噪点不炸。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.NIGHT),
                maxLuma = 0.3f,
                weight = 30
            )
        ),
        Technique(
            id = "night_car_trails",
            title = "车流轨迹：慢门要稳，别追车",
            category = TechCategory.NIGHT,
            oneLiner = "固定机位，等车自己走",
            whenTo = "在路口、高架下拍车灯轨迹时用。把手机固定住，曝光 2 到 8 秒，让车从画面一侧穿到另一侧。",
            steps = listOf(
                "把手机架在三脚架或窗台上，镜头别动。",
                "快门设 2 到 8 秒，ISO 降到最低。",
                "等一辆车完整穿过画面再按，别追着车转手机。"
            ),
            params = listOf(
                "快门" to "2~8 秒",
                "ISO" to "最低",
                "机位" to "三脚架固定"
            ),
            why = "车灯在曝光期间持续移动，感光元件把它的位置依次记录下来，形成一条连续光带；静止的建筑和路面没有位移，保持清晰。机位一动，所有静止物也跟着拖影，轨迹就不干净了。",
            mistakes = listOf(
                "手持拍车流，整张图都在抖",
                "曝光太短，车灯只留下断点"
            ),
            check = "看车灯是一条连续亮线，建筑边缘清楚，没有整体拖影。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.NIGHT),
                requiresLongExposure = true,
                maxLuma = 0.35f,
                weight = 26
            )
        ),
        Technique(
            id = "night_blue_hour",
            title = "蓝调时刻比全黑好拍",
            category = TechCategory.NIGHT,
            oneLiner = "太阳落山后再等二十分钟",
            whenTo = "天刚黑、城市灯亮起来时用。太阳落山后二十到四十分钟，天空还有蓝色，灯光已经亮，光比最舒服。",
            steps = listOf(
                "日落前先找好机位，别等黑了再找。",
                "太阳落下后别走，等天空变成深蓝色。",
                "以天空亮度为准，适当降低曝光让灯不过曝。"
            ),
            params = listOf(
                "时间" to "日落后 20~40 分钟",
                "曝光" to "-0.7~-1.5EV",
                "白平衡" to "日光/阴天"
            ),
            why = "全黑时天空没有光，建筑和路灯的亮度差拉得很大，传感器只能保一头。蓝调时刻天空还留着均匀的散射光，给暗部补了底，灯光又足够亮，画面两端的细节能同时留下。",
            mistakes = listOf(
                "天全黑了才拍，暗部一片死黑",
                "曝光按路灯来，天空糊成一块白"
            ),
            check = "看天空是深蓝不是死黑，灯牌字还能看清，暗部有楼体轮廓。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.NIGHT),
                maxLuma = 0.4f,
                minDynamicRange = 0.3f,
                weight = 20
            )
        ),
        Technique(
            id = "night_portrait_no_direct_flash",
            title = "夜景人像别直闪，找环境光",
            category = TechCategory.NIGHT,
            oneLiner = "借路灯，别拿闪光怼脸",
            whenTo = "晚上拍人像、背景有灯光时用。让人站在路灯、橱窗或广告牌前，用环境光照明，别开手机直闪。",
            steps = listOf(
                "找一块亮而不刺眼的橱窗或灯箱，让人侧对光源。",
                "关掉闪光灯，点人脸测光。",
                "背景留一点灯，别把人拍在纯黑里。"
            ),
            params = listOf(
                "闪光" to "关",
                "光源" to "橱窗/路灯",
                "曝光补偿" to "+0.3~+1EV"
            ),
            why = "手机直闪是机顶小光源，离脸近、面积小，光硬且只照亮前景，背景立刻变黑，人像像贴上去的。环境光面积大、方向明确，脸上有过渡，背景灯还能提供层次；只要让人靠近光源，亮度就够。",
            mistakes = listOf(
                "开直闪拍夜景，脸白背景黑",
                "人站在灯正下方，眼窝和鼻子下面全是黑影"
            ),
            check = "看脸有没有油光和硬阴影，背景灯还看得见，人像和背景不脱节。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.NIGHT),
                minSkinRatio = 0.08f,
                weight = 22
            )
        ),

        // ---------------- 食物 ----------------
        Technique(
            id = "food_angle_45_top",
            title = "食物：45 度与正俯视，别站着拍",
            category = TechCategory.FOOD,
            oneLiner = "先坐下，再决定角度",
            whenTo = "拍一桌菜、咖啡或甜点时用。有高度的食物用 45 度，平铺的餐盘用正俯视，别站着随手往下按。",
            steps = listOf(
                "先坐下，让镜头和桌面差不多高。",
                "有层次的菜用 45 度，让盘子边缘和食物高度都露出来。",
                "平铺的套餐把手机端平，从正上方拍。"
            ),
            params = listOf(
                "角度" to "45 度/正俯视",
                "焦距" to "2 倍减少畸变",
                "机位" to "与桌面同高"
            ),
            why = "站着俯拍时，镜头到桌面的距离远，盘子被压扁，食物的高度和层次消失。45 度能同时看到顶面和侧面，正俯视适合展示摆盘；镜头放低后，近处的食物变大，画面才有主次。",
            mistakes = listOf(
                "站着把手机往前伸，桌子拍成梯形",
                "每道菜都用同一个角度，整组图很平"
            ),
            check = "看盘子的圆有没有被拉扁，食物有高度，画面没有明显透视变形。",
            trigger = TechTrigger(
                minSaturation = 0.4f,
                weight = 18
            )
        ),
        Technique(
            id = "food_side_light",
            title = "侧光出质感，别用顶灯",
            category = TechCategory.FOOD,
            oneLiner = "让光从盘子侧面来",
            whenTo = "拍烤肉、面包、有纹理的菜时用。把盘子移到窗边，让光从一侧扫过表面，别坐在正下方有射灯的座位。",
            steps = listOf(
                "把盘子挪到窗边，让光从左侧或右侧进来。",
                "在暗侧放一张白纸或白盘，补一点反光。",
                "转盘子的角度，直到表面纹理最清楚。"
            ),
            params = listOf(
                "光源" to "窗光侧面",
                "补光" to "白纸/白盘",
                "角度" to "光与镜头约 90 度"
            ),
            why = "侧光斜着扫过食物表面，凸起的纹理和凹陷处亮度不同，微小的高低差被放大成明暗差；顶光从上往下照，阴影落在食物自己身上，纹理反而被压平，还会在盘子里留下难看的硬影。",
            mistakes = listOf(
                "开着餐厅顶灯拍，盘子里全是一块亮斑",
                "光从正前方来，食物平得像贴纸"
            ),
            check = "看面包或烤肉的表面，纹理有亮有暗，暗部没有死黑，就算有质感。",
            trigger = TechTrigger(
                maxLuma = 0.85f,
                minSaturation = 0.45f,
                weight = 20
            )
        ),
        Technique(
            id = "food_no_flash_soft",
            title = "关闪光，用窗光或纸巾柔化",
            category = TechCategory.FOOD,
            oneLiner = "小光源会把菜拍油",
            whenTo = "在室内拍食物、发现菜面反光刺眼时用。关掉手机闪光，改用窗光；实在要补光，就隔一层纸巾或白布。",
            steps = listOf(
                "先关闪光灯，把菜移到窗边。",
                "没有窗光就用另一台手机的手电，隔一张纸巾照向天花板。",
                "别把光源直接对着菜，观察反光变成一片柔光再按。"
            ),
            params = listOf(
                "闪光" to "关",
                "补光" to "纸巾/白布柔化",
                "方向" to "侧上方"
            ),
            why = "闪光灯和手电都是小面积光源，照在油亮的菜面上会形成一块高亮镜面反射，颜色被白光冲掉。隔一层纸巾后，发光面积变大，反射从一点变成一片，亮度更均匀，菜的颜色才留得住。",
            mistakes = listOf(
                "开闪光直拍，菜面反光过曝",
                "用纸巾包住灯太近，纸巾烤黄甚至起火"
            ),
            check = "看菜面最亮处能不能看到纹理，反光是一大片柔光而不是白点。",
            trigger = TechTrigger(
                maxLuma = 0.8f,
                weight = 14
            )
        ),

        // ---------------- 街头 ----------------
        Technique(
            id = "street_compose_then_wait",
            title = "先构图，再等人走进来",
            category = TechCategory.STREET,
            oneLiner = "先找背景，再等主角",
            whenTo = "拍街头、商场、地铁口时用。先找好有光和有结构的背景，把构图定住，再等一个合适的人走进画面。",
            steps = listOf(
                "先找一面有光、有颜色的墙或一段斑马线。",
                "把画面构好，人物位置留空。",
                "举着手机等，看到人进入预留位置再按。"
            ),
            params = listOf(
                "机位" to "先固定",
                "对焦" to "预对焦到等待位置",
                "连拍" to "开"
            ),
            why = "街头瞬间来得快，等看到人再构图，手忙脚乱容易切头切脚。先把背景、光线和人物落点定住，身体不用再动，只剩按快门；人进入预设位置时，画面结构已经成立，成功率比追着拍高。",
            mistakes = listOf(
                "看到人再举手机，永远慢半拍",
                "背景里全是杂物，人等进来也没用"
            ),
            check = "看人物有没有完整落在预留位置，背景线条没被切断，画面是完整的。",
            trigger = TechTrigger(
                minEdgeDensity = 0.32f,
                weight = 22
            )
        ),
        Technique(
            id = "street_peak_action",
            title = "抓动作顶点，别在动作中间按",
            category = TechCategory.STREET,
            oneLiner = "等他抬脚到最高点",
            whenTo = "拍走路、跳跃、骑车的人时用。观察动作循环，在脚抬到最高、身体最舒展的那一下按快门。",
            steps = listOf(
                "先看两遍人物的动作节奏，找到最高点。",
                "用连拍，从动作快到位时开始按。",
                "别在人物刚起步或落地砸实的时候按。"
            ),
            params = listOf(
                "连拍" to "开",
                "快门" to "1/250 以上",
                "预判" to "提前半秒"
            ),
            why = "动作顶点时，人物速度暂时接近零，四肢展开、轮廓最清楚；动作中间速度最快，快门再快也容易拖影，姿态也常常缩成一团。提前半秒连拍，能把顶点前后的几帧都收进来。",
            mistakes = listOf(
                "看到动作结束才按，永远晚一步",
                "用单张慢慢对焦，错过最高点"
            ),
            check = "看人物四肢有没有完全展开，手脚没有拖影，姿态不别扭。",
            trigger = TechTrigger(
                minEdgeDensity = 0.26f,
                weight = 20
            )
        ),
        Technique(
            id = "street_get_closer",
            title = "靠到 3 米内，别用变焦偷拍",
            category = TechCategory.STREET,
            oneLiner = "走近，别隔着马路拉焦距",
            whenTo = "拍街头人物、摊贩、路边的狗时用。走到三米内，用主摄或 2 倍拍，别站在马路对面把画面拉大。",
            steps = listOf(
                "先观察，确认对方不反感，再慢慢靠近。",
                "把手机放低到胸口，别举起来怼着人。",
                "走到三米内再按，用主摄或 2 倍，不用 5 倍以上。"
            ),
            params = listOf(
                "距离" to "3 米内",
                "焦距" to "1~2 倍",
                "姿态" to "手机放低"
            ),
            why = "手机的长焦大多是数码裁切，拉得越远，像素越少、噪点越多，画面还会失去环境信息。走近后主摄的传感器面积和进光量都用得上，人物和背景的比例接近肉眼，街头的空间感才留得住。",
            mistakes = listOf(
                "站马路对面拉 10 倍，人物糊成一团",
                "镜头直接怼脸，引起对方反感"
            ),
            check = "看人物和环境的关系，人物清楚、背景还能认出是哪里，画质不糊。",
            trigger = TechTrigger(
                minEdgeDensity = 0.22f,
                maxLuma = 0.75f,
                weight = 18
            )
        ),

        // ---------------- 宠物小孩 ----------------
        Technique(
            id = "action_freeze_250",
            title = "快门 1/250 起，别让动作糊",
            category = TechCategory.ACTION,
            oneLiner = "动作快，快门先上去",
            whenTo = "拍跑动的小孩、宠物、运动时用。先把快门提到 1/250 以上，再考虑 ISO 和画质，别让动作糊掉。",
            steps = listOf(
                "进专业模式，把快门拨到 1/250 或更快。",
                "ISO 先设自动，画面暗就让它升。",
                "光线太暗时换到窗边或户外，别硬降快门。"
            ),
            params = listOf(
                "快门" to "1/250 以上",
                "ISO" to "自动/400 起",
                "对焦" to "连续对焦"
            ),
            why = "运动物体的像在传感器上移动，快门时间越长，移动距离越大，边缘就被抹开。1/250 秒能把多数跑跳动作的位移压在一个像素级范围内；先保快门再降 ISO，是因为糊掉的瞬间无法靠后期救回来。",
            mistakes = listOf(
                "为了低 ISO 把快门降到 1/60，动作全糊",
                "等动作开始才调参数，来不及"
            ),
            check = "放大看手脚和毛发边缘，动作停住没有拖影，噪点可以接受。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.ACTION),
                minEdgeDensity = 0.34f,
                weight = 26
            )
        ),
        Technique(
            id = "action_get_low",
            title = "蹲下平视，别俯拍宠物和小孩",
            category = TechCategory.ACTION,
            oneLiner = "把镜头降到他们的眼睛高度",
            whenTo = "拍宠物、小孩、低矮的主体时用。蹲下或趴下，让镜头和他们的眼睛齐平，别站着往下拍。",
            steps = listOf(
                "先蹲下，把手机降到孩子或宠物的眼睛高度。",
                "镜头稍微离远一点，留出他们活动的空间。",
                "等他们看向镜头或看向别处，再按。"
            ),
            params = listOf(
                "机位" to "眼睛高度",
                "距离" to "1~2 米",
                "角度" to "平视"
            ),
            why = "站着俯拍时，镜头看到的是头顶和后背，眼睛被压小，人物显得矮；平视时镜头进入他们的世界，眼睛和表情成为画面中心，背景也从地板变成远处的空间，画面更有故事。",
            mistakes = listOf(
                "站着俯拍，孩子只剩一个大头顶",
                "趴得太低仰拍，鼻孔和下巴被放大"
            ),
            check = "看镜头是不是和眼睛差不多高，表情清楚，背景不是一片地板。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.ACTION),
                minEdgeDensity = 0.22f,
                weight = 18
            )
        ),
        Technique(
            id = "action_prefocus_burst",
            title = "预对焦加连拍，别等对上再按",
            category = TechCategory.ACTION,
            oneLiner = "先对好位置，再等他跑过来",
            whenTo = "拍来回跑动的小孩或宠物时用。先把焦点锁在他们会经过的位置，等主体进入焦平面再连拍。",
            steps = listOf(
                "找一个他们会反复经过的点，比如门口或玩具旁。",
                "长按对焦点锁定，或切换到连续对焦。",
                "主体进入画面就按住快门连拍，别等一张张对焦。"
            ),
            params = listOf(
                "对焦" to "预对焦/连续",
                "连拍" to "按住不放",
                "快门" to "1/250 以上"
            ),
            why = "手机对焦要来回找对比度，主体快速移动时，对焦行程常常跟不上，按下去那一下正好是虚的。预对焦把焦距固定在一个平面上，主体穿过这个平面时一定是清楚的，连拍再从中挑表情最好的那张。",
            mistakes = listOf(
                "每张都重新对焦，错过动作",
                "连拍按一下就松，只拍到一张"
            ),
            check = "看连拍里有没有至少一张眼睛清楚、动作完整，背景虚化位置一致。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.ACTION),
                minEdgeDensity = 0.3f,
                weight = 22
            )
        ),

        // ---------------- 微距细节 ----------------
        Technique(
            id = "macro_close_steady_manual",
            title = "微距：贴近、稳住、手动对焦",
            category = TechCategory.MACRO,
            oneLiner = "离得够近，手别抖",
            whenTo = "拍花蕊、昆虫、首饰、文字细节时用。手机贴近到能对焦的最近距离，双手稳住，改用手动对焦慢慢找。",
            steps = listOf(
                "把手机慢慢靠近主体，直到画面刚变虚。",
                "退回一点点，找到能合焦的最近位置。",
                "切手动对焦，轻微前后移动手机，直到细节最锐。"
            ),
            params = listOf(
                "距离" to "最近对焦距离",
                "对焦" to "手动",
                "支撑" to "双手/桌面"
            ),
            why = "微距的景深只有几毫米，自动对焦常常在前后景之间来回找，按下时已经跑焦。手动锁定后，靠身体前后移动改变物距，比拧对焦环更稳；同时贴近拍摄让主体在传感器上占更多像素，细节才出得来。",
            mistakes = listOf(
                "开着自动对焦连拍，十张九张虚",
                "离得太近超过最近对焦距离，怎么都对不上"
            ),
            check = "放大看花蕊或文字，边缘锐利，焦点落在你想强调的那一层。",
            trigger = TechTrigger(
                minEdgeDensity = 0.45f,
                weight = 24
            )
        ),
        Technique(
            id = "macro_backlit_translucent",
            title = "逆光拍透光物，让纹理亮起来",
            category = TechCategory.MACRO,
            oneLiner = "把光放在叶子后面",
            whenTo = "拍树叶、花瓣、玻璃、水母等半透明主体时用。让光源在主体后面，你从正面拍，别让光直接进镜头。",
            steps = listOf(
                "找到主体后面的光源，比如太阳或窗户。",
                "用主体挡住光源，别让太阳直接露出来。",
                "对人脸或主体测光，适当减曝光保住纹理。"
            ),
            params = listOf(
                "光位" to "逆光/侧逆光",
                "曝光" to "-0.3~-1EV",
                "背景" to "暗背景"
            ),
            why = "半透明物体的颜色来自穿过它的光。顺光时你看到的是表面反射，纹理被压平；逆光时光线穿过叶脉和花瓣，内部结构吸收一部分波长，透出来的颜色更饱和。用暗背景衬底，亮部边缘会更清楚。",
            mistakes = listOf(
                "太阳直接进镜头，画面起雾发白",
                "背景太亮，透光边缘和背景糊在一起"
            ),
            check = "看叶片脉络是否发亮、颜色是否透，背景暗且没有大面积眩光。",
            trigger = TechTrigger(
                minEdgeDensity = 0.4f,
                minHighlightClip = 0.04f,
                weight = 20
            )
        ),

        // ---------------- 剪影 ----------------
        Technique(
            id = "silhouette_meter_sky",
            title = "剪影：对天空测光，压 -2EV",
            category = TechCategory.SILHOUETTE,
            oneLiner = "让天正常，人变黑",
            whenTo = "日落、逆光、想让主体只剩轮廓时用。对天空亮部测光，再把曝光压一到两档，直到主体变成干净的黑形。",
            steps = listOf(
                "点画面里的天空，不要点主体。",
                "把曝光补偿往下压 1 到 2 档。",
                "检查主体边缘，别和背景的暗部粘在一起。"
            ),
            params = listOf(
                "测光" to "天空亮部",
                "曝光补偿" to "-1~-2EV",
                "背景" to "亮天空/水面"
            ),
            why = "剪影不是把主体拍黑，而是让背景亮到足够，主体相对变暗。对天空测光时，相机按亮部曝光，主体原本就比天空暗好几档，自然落成黑形；再压曝光是为了让天空颜色更浓，轮廓边缘更干净。",
            mistakes = listOf(
                "对主体测光，天全白、人还亮着",
                "主体和背景暗部重叠，轮廓看不出来"
            ),
            check = "看主体是不是完整黑形，边缘和背景分得开，天空还有颜色。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.BACKLIT),
                minHighlightClip = 0.06f,
                weight = 24
            )
        ),
        Technique(
            id = "silhouette_clean_shape",
            title = "轮廓要简洁，别让手脚糊成一团",
            category = TechCategory.SILHOUETTE,
            oneLiner = "侧身站，把手脚分开",
            whenTo = "拍剪影、人物或物体只剩外形时用。让人侧身站，手脚和身体之间留出空隙，别让四肢叠在一起。",
            steps = listOf(
                "让人侧身站，别正对镜头。",
                "手臂稍微离开身体，腿一前一后。",
                "从取景框里看轮廓，把重叠的部分错开。"
            ),
            params = listOf(
                "姿态" to "侧身",
                "四肢" to "留空隙",
                "机位" to "平视"
            ),
            why = "剪影没有颜色和细节，观众只能靠外轮廓认人。正身站立时，手臂贴在躯干上，腿也叠在一起，外形变成一块没有信息的黑块；侧身并把手脚错开，轮廓出现缺口和曲线，形状才可辨。",
            mistakes = listOf(
                "正面站得笔直，剪影像一根柱子",
                "头顶和远处的树重叠，轮廓缺一块"
            ),
            check = "眯眼看黑形，能认出是人在做什么动作，四肢没有粘成一片。",
            trigger = TechTrigger(
                maxLuma = 0.4f,
                weight = 16
            )
        ),

        // ---------------- 用光 ----------------
        Technique(
            id = "light_hard_soft",
            title = "硬光还是软光，看影子边缘",
            category = TechCategory.LIGHT,
            oneLiner = "影子边锐，光就硬",
            whenTo = "拿不准现场光线性格时用。看影子边缘：边缘锐利是硬光，边缘发虚是软光，再决定让人站哪。",
            steps = listOf(
                "先看地面或墙上的影子，边缘是锐还是虚。",
                "硬光就找阴影或加柔光，软光可以直接拍。",
                "拍人像时，硬光让脸转开一点，别正对光源。"
            ),
            params = listOf(
                "判断" to "影子边缘",
                "硬光" to "遮/柔化",
                "软光" to "直接用"
            ),
            why = "光源面积相对主体越小，光线方向越集中，影子边缘越锐；面积越大，光线从多个方向到达主体，影子边缘被填亮变虚。硬光对比强、纹理清楚，软光过渡柔、适合人像，判断标准就是影子边缘。",
            mistakes = listOf(
                "正午顶光直接拍人，眼窝和鼻子下面全是硬影",
                "以为阴天没光，其实云层就是一块大柔光板"
            ),
            check = "看影子边缘，能说清是硬是软，脸上的明暗过渡和判断一致。",
            trigger = TechTrigger(weight = 30)
        ),
        Technique(
            id = "light_side_backlit",
            title = "侧逆光出层次，别让光从正后方来",
            category = TechCategory.LIGHT,
            oneLiner = "让光从斜后方擦过",
            whenTo = "拍人像、风光想有轮廓光时用。让光源在主体斜后方，和镜头大约成 120 度，别完全正对镜头。",
            steps = listOf(
                "让主体背对太阳，但身体转开约 30 度。",
                "镜头避开直射光，必要时用手或遮光罩挡一下。",
                "对主体暗部测光，别让轮廓光过曝。"
            ),
            params = listOf(
                "光位" to "侧逆光 120 度",
                "测光" to "主体暗部",
                "遮光" to "手/遮光罩"
            ),
            why = "侧逆光从斜后方来，主体边缘被照亮，形成一条亮边，把主体和背景分开；正面还留有环境光，五官不会全黑。完全正逆光时，光源直射镜头，眩光把对比度冲掉，轮廓也容易和背景亮部粘在一起。",
            mistakes = listOf(
                "太阳正对镜头，画面发灰起雾",
                "只保轮廓光，脸黑得看不出表情"
            ),
            check = "看主体边缘有没有一圈亮边，脸还保留细节，画面没有大面积眩光。",
            trigger = TechTrigger(
                minHighlightClip = 0.06f,
                weight = 34
            )
        ),
        Technique(
            id = "light_white_add_black_minus",
            title = "白加黑减：雪和夜不是同一个曝光",
            category = TechCategory.LIGHT,
            oneLiner = "拍白要加，拍黑要减",
            whenTo = "拍雪景、白墙、黑衣服或夜景时用。大面积白色要加曝光，大面积黑色要减曝光，别完全信自动测光。",
            steps = listOf(
                "先看画面里白色或黑色占多大面积。",
                "白色为主就加 0.7 到 1.5 档，黑色为主就减 0.7 到 1.5 档。",
                "拍完看直方图，别让一端堆死。"
            ),
            params = listOf(
                "白场" to "+0.7~+1.5EV",
                "黑场" to "-0.7~-1.5EV",
                "检查" to "直方图"
            ),
            why = "相机把画面平均成 18% 灰。雪地反射率高，自动测光会把它压成灰；黑衣服反射率低，又会被提亮成灰。按「白加黑减」补偿，是让白色回到白、黑色回到黑，感光元件的中间调才不被误判。",
            mistakes = listOf(
                "拍雪不加曝光，雪是脏灰色",
                "拍夜景一直加曝光，灯光全爆"
            ),
            check = "看雪是不是白得干净、黑衣服有没有细节，直方图两端没有大面积堆积。",
            trigger = TechTrigger(weight = 26)
        ),

        // ---------------- 室内弱光 ----------------
        Technique(
            id = "indoor_one_color_temp",
            title = "室内弱光：只留一种光源",
            category = TechCategory.INDOOR,
            oneLiner = "关掉混色灯，只留一种",
            whenTo = "在餐厅、客厅、商场等混合光环境拍人时用。关掉一部分灯，只留窗光或一种色温的灯，别让黄光和蓝光一起照脸。",
            steps = listOf(
                "先看脸上有几种颜色的光，找出最亮的一种。",
                "关掉其他灯，或拉窗帘只留窗光。",
                "白平衡选自动或对应色温，别让脸一半黄一半蓝。"
            ),
            params = listOf(
                "光源" to "只留一种",
                "白平衡" to "自动/对应色温",
                "补光" to "白墙反光"
            ),
            why = "不同色温的光混在一起，脸的一侧偏黄、一侧偏蓝，白平衡只能选一个基准，另一侧永远不正。只留一种光源后，整张脸的色温统一，后期也只需要一次校正；弱光下减少光源还能降低噪点。",
            mistakes = listOf(
                "开着暖灯又开窗，脸一半黄一半蓝",
                "把所有灯关掉，只剩手机屏幕补光，脸发青"
            ),
            check = "看脸的两侧，颜色一致没有一边黄一边蓝，白墙也是同一个白。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.INDOOR),
                maxLuma = 0.35f,
                weight = 26
            )
        ),
        Technique(
            id = "indoor_lock_white_balance",
            title = "锁定白平衡，别让它来回跳",
            category = TechCategory.INDOOR,
            oneLiner = "对准白墙锁一下",
            whenTo = "室内拍视频或连拍、发现颜色一会儿冷一会儿暖时用。先对准白纸或白墙锁定白平衡，再开始拍。",
            steps = listOf(
                "找一张白纸或一面白墙，让它在画面中间。",
                "进入专业模式，把白平衡切到手动并锁定。",
                "回到拍摄画面，确认颜色不再跳再按录制。"
            ),
            params = listOf(
                "白平衡" to "手动锁定",
                "基准" to "白纸/白墙",
                "色温" to "按现场微调"
            ),
            why = "自动白平衡每一帧都在猜现场色温，画面里出现不同颜色的物体时，它会跟着改，导致肤色忽冷忽暖。锁定后，相机不再重新计算，整段素材颜色一致，后期调色时也不用逐段追着改。",
            mistakes = listOf(
                "自动白平衡拍完整段，颜色一直在变",
                "对着彩色墙锁白平衡，整段偏色"
            ),
            check = "录十秒回放，白墙和肤色没有忽冷忽暖，颜色稳定。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.INDOOR),
                maxLuma = 0.45f,
                weight = 20
            )
        ),

        // ---------------- 基本功 ----------------
        Technique(
            id = "basic_shutter_then_iso",
            title = "先定快门，再用 ISO 补亮度",
            category = TechCategory.BASIC,
            oneLiner = "快门管动静，ISO 管亮度",
            whenTo = "用专业模式、不知道先调哪个参数时用。先按主体动不动定快门，再用 ISO 把亮度补回来，别反过来。",
            steps = listOf(
                "先看主体：静止就 1/60 左右，走动就 1/250 起。",
                "定好快门后，再调 ISO 让画面亮度合适。",
                "ISO 太高就找光或放慢快门，别硬扛。"
            ),
            params = listOf(
                "顺序" to "快门→ISO",
                "静止" to "1/60 左右",
                "走动" to "1/250 起"
            ),
            why = "快门决定运动物体在曝光期间移动多少，定低了会糊，而且糊掉无法恢复；ISO 只影响亮度和噪点，噪点还能靠多帧合成压一部分。所以先把不可逆的快门定在对的位置，再用可调的 ISO 补亮度。",
            mistakes = listOf(
                "先把 ISO 拉到最低，快门慢到 1/15，人一动就糊",
                "快门定太快，ISO 飙到 6400，噪点炸开"
            ),
            check = "看主体边缘有没有拖影，亮度够看，ISO 在可接受范围，顺序就对了。",
            trigger = TechTrigger(
                maxLuma = 0.35f,
                weight = 34
            )
        ),
        Technique(
            id = "basic_protect_highlights",
            title = "宁可略暗，别让高光死白",
            category = TechCategory.BASIC,
            oneLiner = "暗能提亮，白救不回",
            whenTo = "拿不准曝光、画面里有窗、灯、白衣服时用。宁可让整体暗半档，也别让最亮的地方一片死白。",
            steps = listOf(
                "先看画面最亮的地方，比如窗、灯、白墙。",
                "把曝光压到高光还有细节，别让直方图右端堆死。",
                "暗部后期能提，提完再看噪点能不能接受。"
            ),
            params = listOf(
                "曝光" to "-0.3~-1EV",
                "检查" to "直方图右端",
                "高光" to "保留细节"
            ),
            why = "感光元件的高光容量有限，亮度超过饱和点后，像素只剩同一个最大值，颜色和纹理都丢了；暗部虽然噪点多，但仍记录着可恢复的差异。所以曝光时优先保住高光，暗部留给后期或多帧合成。",
            mistakes = listOf(
                "为了让暗部亮，把窗户拍成一块白板",
                "看屏幕觉得亮就加曝光，回家发现高光全丢"
            ),
            check = "看窗户、灯罩、白衣服，最亮处还有纹理，没有整片纯白。",
            trigger = TechTrigger(
                minHighlightClip = 0.04f,
                weight = 30
            )
        ),
        Technique(
            id = "basic_clean_lens",
            title = "擦镜头：雾感多半来自指纹",
            category = TechCategory.BASIC,
            oneLiner = "拍之前先擦一下镜头",
            whenTo = "发现画面发灰、灯光周围有一圈雾、怎么调都不通透时用。先用干净衣角或镜头布擦镜头，再拍一张对比。",
            steps = listOf(
                "用干净的超细纤维布或纯棉衣角擦镜头。",
                "对着光看镜头表面，确认没有指纹和油膜。",
                "擦完先拍一张，和之前的照片对比通透度。"
            ),
            params = listOf(
                "工具" to "超细纤维布",
                "动作" to "单向轻擦",
                "检查" to "对光看镜头"
            ),
            why = "指纹在镜头上形成一层油膜，光线穿过时在膜层内散射，等于给画面加了一层全局低频眩光，对比度和饱和度都被压掉，直方图两端也会被压缩。擦掉油膜后散射减少，画面才恢复通透。",
            mistakes = listOf(
                "用粗糙纸巾干擦，镜头留下细划痕",
                "只擦中间，边缘油膜还在，画面依旧发雾"
            ),
            check = "对着灯拍一张，灯光边缘干净、没有一圈雾，画面灰雾明显减少。",
            trigger = TechTrigger(
                maxDynamicRange = 0.45f,
                weight = 26
            )
        ),
        Technique(
            id = "basic_ev_order",
            title = "曝光补偿的判断顺序：先主体后背景",
            category = TechCategory.BASIC,
            oneLiner = "先保主体，再管背景",
            whenTo = "用自动模式、拿不准该加还是该减曝光时用。先看主体有没有细节，再看背景能不能接受，别一上来就拉全局。",
            steps = listOf(
                "先点主体测光，看主体亮度是否合适。",
                "主体对了再看背景，背景过曝就换机位或开 HDR。",
                "最后微调曝光补偿，别一次拉超过一档。"
            ),
            params = listOf(
                "顺序" to "主体→背景",
                "步长" to "0.3~0.7EV",
                "辅助" to "HDR/换机位"
            ),
            why = "自动曝光按全画面平均值决定亮度，背景很亮时会把主体压暗，背景很暗时又会把主体提亮。先对主体测光，是让最重要的信息落在中间调；再看背景决定要不要妥协，顺序反了就会为了背景牺牲主体。",
            mistakes = listOf(
                "先看背景亮不亮，主体黑成剪影",
                "一次拉两档曝光，背景和主体一起失控"
            ),
            check = "看主体细节是否完整，背景亮部没有大面积死白，曝光补偿没有来回跳。",
            trigger = TechTrigger(
                minDynamicRange = 0.55f,
                weight = 28
            )
        )
    )

    /**
     * 技法总库：39 条老库 + 44 条 v1.5 新库 = 83 条。
     *
     * `by lazy` 而不是直接赋值，是为了绕开两个 object 的初始化顺序问题：
     * [TechniqueBookV15] 的 `combined` 反过来又指向这里。惰性求值让谁先被
     * 访问都能算出同一个结果，而不会在 `<clinit>` 里拿到半成品。
     */
    val all: List<Technique> by lazy { legacy + TechniqueBookV15.list }
}
