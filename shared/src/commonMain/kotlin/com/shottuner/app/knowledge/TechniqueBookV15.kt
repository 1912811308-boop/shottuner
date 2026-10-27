package com.shottuner.app.knowledge

import com.shottuner.app.model.SceneType

/**
 * v1.5 新增的 44 条技法。
 *
 * ## 为什么不塞进 `PhotographyBook.all`
 * 老文件里那 39 条被 5 个测试和调度器按 id 引用，动它的风险远高于收益。
 * 这里独立成一个 object，由 [all] 与老库合并 —— 老库一行不改。
 *
 * ## 文案来源与字数口径
 * 全部来自 `docs/TECHNIQUE-v15.md`（产品经理交付，842 行，字数自查 0 违规）。
 * 转写时**逐个字段照抄**，只在三处做了机械处理：
 * 1. 去掉每段末尾的「（N 字）」字数标注 —— 那是校验用的，不该进 UI；
 * 2. `params` 的 `键=值；键=值` 拆成 `Pair` 列表；
 * 3. `trigger` 的 `scenes=A, B；key=value` 拆成构造参数。
 *
 * ## 分类分布（必须与产品口径一致，`TechniqueBookV15Test` 会数）
 * portrait 10 / move 8 / selfie 5 / pitfall 4 / landscape 4 / params 3 /
 * night 3 / light 3 / street 2 / food 2 = 44
 *
 * ## 三条「仅浏览」条目
 * [BROWSE_ONLY_IDS] 里的三条**不得出现在任何推送池**（`PromptEngine` 的规则、
 * `VideoMovePlanner` 的运镜池、`TechniqueMatcher` 的推荐池）。理由见该常量的注释。
 */
object TechniqueBookV15 {

    /**
     * 只做浏览、不进任何推送池的三条。
     *
     * - `night_slow_shutter`：触发阈值 `maxLuma=0.15` 比 R03 欠曝规则（0.18）更严，
     *   而 R03 是 URGENT 且先求值，它永远抢不到出牌机会，留着只会误导调参；
     * - `move_no_whip_pan`：没有角速度信号，感知不到「在甩镜」，只能靠猜；
     * - `pitfall_beauty_overdo`：美颜强度是相机 App 的设置项，不在我们的信号里。
     *
     * 三条的文案本身有价值，所以保留在技法页供浏览，只是不进实时推送。
     */
    val BROWSE_ONLY_IDS: Set<String> = setOf(
        "night_slow_shutter",
        "move_no_whip_pan",
        "pitfall_beauty_overdo"
    )

    /** 自拍类。本 App 只有后摄通路，这 5 条仅浏览。 */
    val SELFIE_IDS: Set<String> = setOf(
        "selfie_arm_extended",
        "selfie_hold_above_eye",
        "selfie_tongue_palate",
        "selfie_eyes_to_lens",
        "selfie_side_light"
    )

    // ── 批次 A：SELFIE 5 条 ────────────────────────────────────────────────────

    private val SELFIE: List<Technique> = listOf(
        Technique(
            id = "selfie_arm_extended",
            title = "手臂伸直再拍，脸不会被拉宽",
            category = TechCategory.SELFIE,
            oneLiner = "手臂伸直，别贴脸",
            whenTo = "用前置自拍、手机离脸很近时用。把手臂完全伸直，让手机和脸保持一臂距离，再用两倍变焦把画面拉回来，脸的边缘就不会被广角撑开。",
            steps = listOf(
                "先看手机离脸有多远，贴着脸就一定要改",
                "手臂完全伸直，镜头对着眼睛高度",
                "切到两倍，用变焦把脸拉回画面里合适的大小"
            ),
            params = listOf(
                "倍率" to "2x",
                "距离" to "约一臂（50cm）",
                "镜头高度" to "略高于眼睛"
            ),
            why = "前置是广角镜头，贴脸拍会产生桶形畸变，把脸边缘往外拉，看起来又宽又扁。拉开距离再变焦，透视就回到接近人眼的比例，这也是为什么别人帮你拍总比自拍好看。",
            mistakes = listOf(
                "手臂弯着、手机贴脸拍，脸被撑大",
                "用 0.5x 自拍，畸变最严重"
            ),
            check = "拍完把照片缩小看，脸的宽度和镜子里接近就对了；脸颊明显外扩说明还是太近。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.PORTRAIT),
                minSkinRatio = 0.04f,
                weight = 20
            )
        ),
        Technique(
            id = "selfie_hold_above_eye",
            title = "手机举过眼睛一点，脸最显小",
            category = TechCategory.SELFIE,
            oneLiner = "举高一点，下巴微收",
            whenTo = "举着手机自拍、想显脸小时用。把手机抬到比眼睛高一掌的位置，镜头略微往下压，同时下巴收一点。",
            steps = listOf(
                "先看手机是不是低于眼睛，低于就一定要抬",
                "把手机抬到比眼睛高一掌，镜头微微下压",
                "下巴收一点，眼睛往上看镜头"
            ),
            params = listOf(
                "高度" to "高过眼睛约一掌",
                "俯角" to "约 15 度",
                "下巴" to "微收"
            ),
            why = "从下往上仰拍时，镜头离下巴比离额头近，下巴被放大、额头被缩小，双下巴和鼻孔都会被强调。抬高到眼睛上方再微微俯拍，比例反过来，额头略大、下颌线收紧，脸看起来更小更立体。",
            mistakes = listOf(
                "从下往上仰拍，双下巴和鼻孔全出来",
                "手机举太高变成俯拍，头大脸小"
            ),
            check = "拍完看下颌线，是往内收的一条弧线就对了；如果看到下巴底下一层肉，说明机位还是太低。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.PORTRAIT),
                minSkinRatio = 0.04f,
                weight = 20
            )
        ),
        Technique(
            id = "selfie_tongue_palate",
            title = "舌头顶住上颚，下颌线更清楚",
            category = TechCategory.SELFIE,
            oneLiner = "舌头顶上颚，别放松",
            whenTo = "自拍或别人帮你拍正脸、想让下颌线清楚时用。舌头轻轻顶住上颚，下巴自然收一点，别用力到脖子发紧。",
            steps = listOf(
                "先放松拍一张，看看下巴下面有没有堆肉",
                "舌头轻顶上颚，下巴跟着自然收一点",
                "脖子别前伸，肩膀往下沉"
            ),
            params = listOf(
                "舌头" to "轻顶上颚",
                "下巴" to "微收",
                "脖子" to "不前伸"
            ),
            why = "舌根连着口底肌肉，舌头放松塌在口腔底部时，下巴下方的软组织会往下堆。把舌头顶住上颚，舌根往上抬，口底肌肉被拉紧，下颌到脖子之间那条线就显出来了，不需要修图。",
            mistakes = listOf(
                "完全放松，下巴赘肉堆一层",
                "用力过猛，脖子青筋都出来了"
            ),
            check = "侧一点看下巴到脖子的转角，是一条清楚的折线就对了；如果是圆的说明舌头没顶上。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.PORTRAIT),
                minSkinRatio = 0.04f,
                weight = 18
            )
        ),
        Technique(
            id = "selfie_eyes_to_lens",
            title = "看镜头别看屏幕，眼神才有神",
            category = TechCategory.SELFIE,
            oneLiner = "盯住镜头那个小孔",
            whenTo = "用前置自拍、反复拍总觉得眼神飘的时候用。按快门前把视线移到屏幕边上那个镜头小孔，别看屏幕里的自己。",
            steps = listOf(
                "先在屏幕里找到前置镜头的位置，通常是个小圆点",
                "把视线移到那个小圆点上，别看画面里的脸",
                "保持住，按下快门再移开眼睛"
            ),
            params = listOf(
                "视线" to "镜头小孔",
                "快门" to "眼神定住后 1 秒",
                "张数" to "连拍 3 张挑一张"
            ),
            why = "屏幕和人眼有十几厘米的距离，看屏幕时视线是向内偏的，照片里会呈现出轻微斜视或眼神发直。盯着镜头孔看，视线方向和镜头光轴重合，瞳孔里能反出光点，眼神才聚得住，看起来像在跟人说话。",
            mistakes = listOf(
                "全程盯着屏幕里自己的脸，眼神发飘",
                "按快门的一瞬间眼神移开了"
            ),
            check = "放大看瞳孔，里面有一个清楚的小白点就对了；眼神偏向一侧说明你还在看屏幕。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.PORTRAIT),
                minSkinRatio = 0.05f,
                weight = 18
            )
        ),
        Technique(
            id = "selfie_side_light",
            title = "找侧前方的光，鼻子才有立体",
            category = TechCategory.SELFIE,
            oneLiner = "转半圈，让光从侧前来",
            whenTo = "在室内或窗边自拍、脸看起来又平又扁时用。转动身体，让窗户或灯落在你的侧前方四十五度，别正面直对光源。",
            steps = listOf(
                "先看脸是不是一片均匀的亮，均匀就说明光太平",
                "转身体，把光挪到侧前方四十五度",
                "暗的那半边脸，用白墙或浅色衣服反一点光"
            ),
            params = listOf(
                "角度" to "侧前方 45 度",
                "光源" to "窗或台灯",
                "补光" to "白墙或浅色衣物"
            ),
            why = "正面平光把鼻梁、颧骨和下颌的起伏全部照平，脸看起来像一张纸，还会把所有毛孔和瑕疵一起照亮。光从侧前方斜着扫过来，亮面和暗面之间出现连续过渡，大脑才能从明暗变化里读出三维形状。",
            mistakes = listOf(
                "正对顶灯自拍，鼻子和眼窝一片白",
                "背对窗户，脸和背景一起黑"
            ),
            check = "看鼻梁两侧，一侧亮一侧有淡淡阴影就对了；如果两边一样亮，说明光还是太平。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.PORTRAIT, SceneType.INDOOR),
                minSkinRatio = 0.04f,
                weight = 20
            )
        )
    )

    // ── 批次 A：CAMERA_MOVE 8 条 ───────────────────────────────────────────────

    private val CAMERA_MOVE: List<Technique> = listOf(
        Technique(
            id = "move_elbow_tucked",
            title = "手肘夹紧身体，画面才不抖",
            category = TechCategory.CAMERA_MOVE,
            oneLiner = "手肘贴住肋骨别伸出去",
            whenTo = "拿手机录像、边走边拍时用。把两个手肘收回来贴住肋骨，用身体带动手机移动，别把手臂伸到前面举着。",
            steps = listOf(
                "先看手臂是不是伸在前面，伸出去就一定要收",
                "两个手肘贴住肋骨，手机离身体一拳远",
                "想改变方向就用腰转，不要用手臂甩"
            ),
            params = listOf(
                "手肘" to "贴肋骨",
                "手机距身体" to "约一拳",
                "转向" to "用腰别用手臂"
            ),
            why = "手臂伸出去时，肩关节成了支点，手臂越长末端摆幅越大，呼吸和心跳都会放大成抖动。手肘贴住肋骨后，手机和躯干变成一个刚性整体，晃动从「手臂末端」降到「整个身体」，幅度小一个量级。",
            mistakes = listOf(
                "双手前伸举着边走边拍",
                "一只手拿手机，另一只手插兜"
            ),
            check = "录完回放看画面边缘的直线，是平的、不上下晃就对了；晃说明手肘没夹住。",
            trigger = TechTrigger(weight = 16)
        ),
        Technique(
            id = "move_small_steps",
            title = "屈膝小碎步走，画面不上下颠",
            category = TechCategory.CAMERA_MOVE,
            oneLiner = "微屈膝，小步挪过去",
            whenTo = "边走边录、镜头跟着人往前移时用。膝盖保持微屈，用脚掌一小步一小步挪，不要大步流星地走。",
            steps = listOf(
                "先走两步看看画面颠不颠，颠就换走法",
                "膝盖微微弯曲，重心压低一点",
                "用脚掌落地，一小步一小步往前挪"
            ),
            params = listOf(
                "膝盖" to "微屈",
                "步幅" to "约常人的一半",
                "落地" to "脚掌先着"
            ),
            why = "正常走路时身体重心每步都会上下起伏几厘米，这个起伏会被完整记录进画面。屈膝把重心压低，同时用脚掌滚动着落地，能把这个上下位移吃掉大半，画面就稳了。",
            mistakes = listOf(
                "大步快走，画面上下颠得人晕",
                "踮着脚走，比正常走更晃"
            ),
            check = "回放时盯住画面里的一条水平线，它不上下浮就对了；浮得厉害说明步子还是太大。",
            trigger = TechTrigger(weight = 16)
        ),
        Technique(
            id = "move_push_in_slow",
            title = "推镜头慢慢靠近，别猛推猛停",
            category = TechCategory.CAMERA_MOVE,
            oneLiner = "八到十秒匀速推近",
            whenTo = "录像要突出一个主体时用。从稍远的位置起步，用八到十秒缓慢靠近，全程速度保持一致，中途不要停顿也不要加速。",
            steps = listOf(
                "先站远一点，让主体在画面里只占一小块",
                "按下录制后停一秒，再开始往前走",
                "用八到十秒走完，速度全程不变",
                "到主体占画面约一半时停住，再停一秒"
            ),
            params = listOf(
                "时长" to "8~10 秒",
                "速度" to "全程一致",
                "起幅落幅" to "各静 1 秒"
            ),
            why = "推镜头的目的是把观众的注意力一点点收拢到一个点上，速度一变，注意力就断了。匀速推进时，主体在画面里的占比单调增加，观众会不自觉地跟着靠近；猛推则像被人推了一把，只剩下惊吓。",
            mistakes = listOf(
                "猛推猛停，像要撞上去",
                "一边走一边变焦，速度完全失控"
            ),
            check = "回放看主体变大的过程，是顺滑地一直变大就对了；中间有卡顿说明手停过。",
            trigger = TechTrigger(weight = 15)
        ),
        Technique(
            id = "move_hold_ends",
            title = "起落幅各停两秒，剪辑才好接",
            category = TechCategory.CAMERA_MOVE,
            oneLiner = "开机先停，收尾再停",
            whenTo = "录任何一段素材时都用得上。按下录制后先原地静止两秒，运镜结束再静止两秒，然后才停止录制。",
            steps = listOf(
                "按下录制，先别动，数两秒",
                "开始运镜，走完全程",
                "到位后定住，再数两秒",
                "最后才按停止"
            ),
            params = listOf(
                "起幅静止" to "2 秒",
                "落幅静止" to "2 秒",
                "单段时长" to "不短于 5 秒"
            ),
            why = "剪辑时需要一段静止画面作为切口，两段素材才能干净地接在一起。如果开机就在动、停手就切，两端都在运动，剪辑师只能靠加转场遮丑，观众看到的就是一堆生硬的切换。",
            mistakes = listOf(
                "一开机就摇，一停手就切",
                "每段只录两三秒，全都剪不动"
            ),
            check = "回放看每段素材的头尾，各有两秒画面几乎不动就对了；从头动到尾说明没留起落幅。",
            trigger = TechTrigger(weight = 14)
        ),
        Technique(
            id = "move_orbit_30deg",
            title = "环绕每秒别超三十度，慢才看清",
            category = TechCategory.CAMERA_MOVE,
            oneLiner = "绕主体慢走，别快转",
            whenTo = "拍产品、拍菜品、拍人或宠物想展示立体感时用。以主体为圆心慢慢横向移动，每秒转过的角度控制在三十度以内。",
            steps = listOf(
                "先站定，确认主体在画面正中",
                "把手机对着主体，用脚横向小步走",
                "全程让主体停在画面同一位置，别越转越偏",
                "转满九十度左右就停，别绕一整圈"
            ),
            params = listOf(
                "速度" to "≤30 度/秒",
                "半径" to "约 1 米",
                "角度" to "90 度以内"
            ),
            why = "环绕镜头让主体背后的环境持续变化，观众能感知到它的体积。但人眼追踪转动画面的能力有限，每秒超过三十度就开始晕，细节全糊成一片，反而看不出东西长什么样。",
            mistakes = listOf(
                "举着手机快速绕圈，画面糊成一片",
                "一边转一边变焦，主体忽大忽小"
            ),
            check = "回放时盯着主体上的一个细节，全程都能看清就对了；看糊了说明转太快。",
            trigger = TechTrigger(weight = 15)
        ),
        Technique(
            id = "move_no_whip_pan",
            title = "竖屏别大幅甩镜，观众跟不上",
            category = TechCategory.CAMERA_MOVE,
            oneLiner = "竖屏少横摇，别快甩",
            whenTo = "竖屏录视频时用。把横向摇摄的幅度收小，也别做那种一秒扫过去的快速甩镜，改用小范围移动或推拉。",
            steps = listOf(
                "先想清楚这段要不要横着扫，不确定就别扫",
                "竖屏把摇摄幅度压到画面宽度的一倍以内",
                "想换视角就切一个镜头，别用甩镜过渡"
            ),
            params = listOf(
                "摇摄幅度" to "≤1 个画面宽",
                "甩镜" to "不用",
                "过渡" to "靠剪辑切"
            ),
            why = "竖屏画幅窄，横向能容纳的信息本来就少，快速甩镜时画面里几乎全是运动模糊。横屏甩镜还能靠宽画幅撑住，竖屏一甩观众只看到一片糊，还会晕。",
            mistakes = listOf(
                "照搬电影里的大幅横摇，竖屏里全糊",
                "用甩镜当转场，观众看得头晕"
            ),
            check = "回放时能看清每一秒画面里是什么就对了；有一两秒完全糊掉就是甩太快。",
            trigger = TechTrigger(weight = 9)
        ),
        Technique(
            id = "move_walk_not_zoom",
            title = "想拉近就走过去，别捏屏幕放大",
            category = TechCategory.CAMERA_MOVE,
            oneLiner = "用脚变焦，别用手指",
            whenTo = "录像时想拍到更近的画面用。放下手指，直接朝主体走过去，让画面自然变大，而不是在屏幕上捏合放大。",
            steps = listOf(
                "先看现在用了几倍，超过两倍就要改",
                "收起捏合的手指，直接往前走",
                "走到主体占画面合适大小就停下"
            ),
            params = listOf(
                "倍率" to "不超过 2x",
                "接近方式" to "走过去",
                "变焦" to "只在静止时微调"
            ),
            why = "手机的长焦大多是主摄裁切出来的，数码变焦放大多少倍，就等于把画面裁掉多少，像素直接损失。走过去是光学上的靠近，画质一点不掉，透视也更自然。",
            mistakes = listOf(
                "全程用数码变焦当长焦使，画面全是大颗粒",
                "一边走一边放大，观感混乱"
            ),
            check = "拍完放大看细节，边缘还清楚就对了；一片模糊说明用了数码变焦。",
            trigger = TechTrigger(weight = 14)
        ),
        Technique(
            id = "move_fixed_support",
            title = "找个支点拍长镜头，别凭空举着",
            category = TechCategory.CAMERA_MOVE,
            oneLiner = "手机架稳，再录长镜头",
            whenTo = "要录延时、长镜头或者原地不动的固定机位时用。把手机靠在栏杆、桌面或墙边，让机身有依托，再按下录制。",
            steps = listOf(
                "先判断这段能不能靠住，能靠就别手举",
                "把手机斜靠或平放在稳固的平面上",
                "调整好角度后别碰手机，用倒计时开始录",
                "录完先停，再拿走手机"
            ),
            params = listOf(
                "支点" to "栏杆/桌面/墙",
                "启动" to "倒计时 3 秒",
                "时长" to "按需要 10 秒以上"
            ),
            why = "手持的抖动频率很低，一秒之内画面只偏一点，单张照片看不出来；但录十几秒之后，这些偏移会累积成明显的漂移，回放时像晕船。有支点就等于把抖动频率和幅度一起归零。",
            mistakes = listOf(
                "手举着拍延时，全废",
                "靠在栏杆上还用手扶着，等于没靠"
            ),
            check = "回放时盯住画面里一个固定物体，它不漂就对了；漂了说明支点不够稳。",
            trigger = TechTrigger(weight = 13)
        )
    )

    // ── 批次 A：PARAMS 3 条 ────────────────────────────────────────────────────

    private val PARAMS: List<Technique> = listOf(
        Technique(
            id = "params_portrait_plus_third",
            title = "人像加三分之一档，皮肤显通透",
            category = TechCategory.PARAMS,
            oneLiner = "EV往上加三分之一档",
            whenTo = "拍人像、脸看起来发灰发暗时用。把曝光补偿往上推三分之一档，用点测光对准人脸，别对着背景测。",
            steps = listOf(
                "先看脸是不是比背景暗，暗就说明测光被背景带跑了",
                "点一下人脸锁定对焦和测光",
                "曝光补偿往上推三分之一档，边推边看脸"
            ),
            params = listOf(
                "EV" to "+0.3",
                "测光" to "人脸点测",
                "ISO" to "100~200"
            ),
            why = "相机按全画面平均值测光，而人脸肤色比平均值亮，不在画面正中时就会被压暗半档到一档。往上推三分之一档，把皮肤拉回中间调偏高一点的位置，看起来通透干净，又不会把白衣服推到过曝。",
            mistakes = listOf(
                "对着背景测光，脸永远是灰的",
                "一次加超过一档，白衣服糊成一片"
            ),
            check = "拍完看脸颊，是干净偏亮的肤色就对了；发灰说明加得不够，惨白说明加多了。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.PORTRAIT),
                minSkinRatio = 0.05f,
                weight = 22
            )
        ),
        Technique(
            id = "params_white_balance_mood",
            title = "白平衡管冷暖，别只信自动",
            category = TechCategory.PARAMS,
            oneLiner = "想暖往黄调，想冷往蓝调",
            whenTo = "拍出来颜色总是不对、或者想主动控制画面情绪时用。想暖一点把白平衡往黄调，想冷一点往蓝调，别全程交给自动。",
            steps = listOf(
                "先看现在的色温是不是你要的感觉",
                "想暖就往黄调两格，想冷就往蓝调两格",
                "一次只调两格，调完退一步再看整体"
            ),
            params = listOf(
                "步长" to "每次 2 格",
                "偏暖" to "往黄调",
                "偏冷" to "往蓝调"
            ),
            why = "自动白平衡的目标是还原真实，它会把画面里所有偏色都纠正掉，包括你想要的黄昏暖调和阴天冷调。手动偏一点，颜色才带情绪。但一次拉太多会变成脏黄或者死蓝，两格两格试最稳。",
            mistakes = listOf(
                "只信自动白平衡，冷暖不由己",
                "一次拉到底，画面脏得救不回来"
            ),
            check = "和肉眼看到的现场比，颜色是同一个方向就对了；差太多说明调过头。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.INDOOR, SceneType.NIGHT),
                weight = 18
            )
        ),
        Technique(
            id = "params_food_plus_third",
            title = "美食曝光加一点，看着更新鲜",
            category = TechCategory.PARAMS,
            oneLiner = "菜要亮一点才好吃",
            whenTo = "拍菜、拍出来总觉得像剩菜时用。把曝光补偿往上推三分之一档，快门放在一百六十分之一秒上下，别让盘子发灰。",
            steps = listOf(
                "先看菜是不是发暗发灰，灰就一定要提",
                "曝光补偿往上推三分之一档",
                "快门放到 1/160 左右，别低于 1/60"
            ),
            params = listOf(
                "EV" to "+0.3",
                "快门" to "1/125~1/200",
                "ISO" to "100~200"
            ),
            why = "人判断食物新不新鲜，很大程度看亮度和饱和度。稍微提亮一点，油脂的高光更亮、蔬菜的绿色更鲜，看着就想吃。但推太多白色盘子会失掉边缘细节，所以只要三分之一档。",
            mistakes = listOf(
                "拍得偏暗，好好的菜像剩菜",
                "加超过一档，白盘子糊成一个白块"
            ),
            check = "看盘子边缘还有没有轮廓，有就对了；糊进背景说明加多了。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.INDOOR),
                weight = 20
            )
        )
    )

    // ── 批次 A：PITFALL 4 条 ───────────────────────────────────────────────────

    private val PITFALL: List<Technique> = listOf(
        Technique(
            id = "pitfall_grid_on",
            title = "先把网格打开，构图才有参照",
            category = TechCategory.PITFALL,
            oneLiner = "网格和水平仪都打开",
            whenTo = "第一次用这台手机拍照、或者拍出来总是歪的时用。进相机设置把网格线和水平仪打开，之后每次取景都有参照。",
            steps = listOf(
                "先看取景画面里有没有九宫格，没有就去开",
                "在相机设置里找到网格线，打开",
                "顺手把水平仪也一起打开"
            ),
            params = listOf(
                "网格" to "开",
                "水平仪" to "开",
                "位置" to "相机设置里"
            ),
            why = "人眼判断水平的能力比想象中差很多，靠手感端平，十次里有三次是歪的。网格线给出固定的横向和纵向参照，三分法、地平线、建筑垂直线这些技巧才有落点，否则全是空谈。",
            mistakes = listOf(
                "网格一直关着，全靠手感瞎拍",
                "只开网格不开水平仪，歪了也不知道"
            ),
            check = "取景时能看到九宫格和水平线就对了；看不到说明还在用默认设置。",
            trigger = TechTrigger(weight = 16)
        ),
        Technique(
            id = "pitfall_auto_everything",
            title = "别什么都交给自动，先学会调EV",
            category = TechCategory.PITFALL,
            oneLiner = "逆光和夜景要手动调",
            whenTo = "遇到逆光、夜景、雪地、演唱会这类光比大的场景时用。别全靠自动模式，至少把曝光补偿捏在自己手里。",
            steps = listOf(
                "先看画面是不是一半死白一半死黑",
                "点屏幕锁定对焦，再上下拖动曝光",
                "逆光往下压，欠曝往上推"
            ),
            params = listOf(
                "逆光" to "EV-0.7~-1.0",
                "欠曝" to "EV+0.5~+0.7",
                "测光" to "点屏锁定"
            ),
            why = "自动测光追求的是全画面平均正确，它不知道你想拍的是人脸还是一盏灯。光比大的时候，这个平均值落在谁身上都不对。手动压或者提一两档，等于直接告诉相机你要保住哪一块。",
            mistakes = listOf(
                "全自动一路按，废片率极高",
                "一遇到要调参数就慌，干脆不调"
            ),
            check = "看你想拍的那块地方有没有细节就对了；它是一片纯白或纯黑，说明测光没管住。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.BACKLIT, SceneType.NIGHT),
                weight = 17
            )
        ),
        Technique(
            id = "pitfall_beauty_overdo",
            title = "美颜别拉满，磨太狠像塑料人",
            category = TechCategory.PITFALL,
            oneLiner = "磨皮降到三成以内",
            whenTo = "用美颜相机、或者开了美颜档位自拍时用。把磨皮强度降到百分之三十以内，大眼瘦脸这些能关就关。",
            steps = listOf(
                "先把磨皮拉到最低，看原生皮肤",
                "再一点点往上加，加到能盖住瑕疵就停",
                "大眼、瘦脸、尖下巴这类能关就关"
            ),
            params = listOf(
                "磨皮" to "≤30%",
                "大眼" to "关",
                "瘦脸" to "关"
            ),
            why = "皮肤之所以看起来像皮肤，靠的是毛孔、绒毛和细小的明暗变化。磨皮把这些高频细节全部抹平，脸就变成一块均匀的色块，反而更像假人，也比真实年龄更显老。",
            mistakes = listOf(
                "磨皮、大眼、瘦脸三样全开",
                "只磨脸不磨脖子，脸和脖子两个色"
            ),
            check = "放大看脸颊，还能看到一点皮肤纹理就对了；一片光滑说明磨过头。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.PORTRAIT),
                minSkinRatio = 0.08f,
                weight = 10
            )
        ),
        Technique(
            id = "pitfall_filter_overdo",
            title = "滤镜只加一点，拉满就丢细节",
            category = TechCategory.PITFALL,
            oneLiner = "饱和对比各加一点就停",
            whenTo = "加滤镜或者后期调色时用。饱和度和对比度各往上加一点就停手，别拉到满格，也别叠好几层滤镜。",
            steps = listOf(
                "先把滤镜强度调到一半以下",
                "饱和度、对比度各加一点就停手",
                "加完退一步看整体，别只盯着局部"
            ),
            params = listOf(
                "滤镜强度" to "≤50%",
                "饱和度" to "+10 以内",
                "对比度" to "+10 以内"
            ),
            why = "饱和度和对比度拉满时，颜色通道会被推到上下限直接截断，原本有的层次全部变成纯色块，暗部死黑、亮部死白，而且截断之后再也救不回来。",
            mistakes = listOf(
                "滤镜开到 100%，画面糊成一片色块",
                "叠三层滤镜，颜色互相打架"
            ),
            check = "看暗部和亮部还有没有层次就对了；糊成一片纯色说明拉过头了。",
            trigger = TechTrigger(weight = 9)
        )
    )

    // ── 批次 B：PORTRAIT 10 条 ─────────────────────────────────────────────────

    private val PORTRAIT: List<Technique> = listOf(
        Technique(
            id = "portrait_headroom",
            title = "头顶留一小格，别让人顶到边",
            category = TechCategory.PORTRAIT,
            oneLiner = "头顶留约五分之一",
            whenTo = "拍半身或全身、人物头顶快顶到画框上沿时用。往后退半步或者把镜头往上抬一点，让头顶和上沿之间留出约五分之一画面。",
            steps = listOf(
                "先看头顶和画框上沿之间有没有空隙",
                "没有就退半步，或者把手机往上抬一点",
                "留到约五分之一画面就够，别留太多"
            ),
            params = listOf(
                "头顶留白" to "约1/5画面",
                "退距" to "约半步",
                "机位" to "略高于眼睛"
            ),
            why = "头顶贴着画框，视线没有出口，人看着就憋屈，像是被硬塞进去的。留出一点空间，画面才有呼吸的位置，也给后期裁切留了余量。但留太多会让人在画面里变小、显得腿短。",
            mistakes = listOf(
                "头顶直接顶到画框边缘，看着压抑",
                "留白超过三分之一，人缩在下面一小块"
            ),
            check = "缩小看整张图，头顶那段空白既不憋屈也不空旷就对了。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.PORTRAIT),
                minSkinRatio = 0.04f,
                weight = 16
            )
        ),
        Technique(
            id = "portrait_feet_anchor",
            title = "脚踩到画面底边，腿才不被截断",
            category = TechCategory.PORTRAIT,
            oneLiner = "脚贴底边，别留空地",
            whenTo = "拍全身、想让人显高时用。让脚踩到画面最底部，构图时把镜头往下压，别让脚下留出一大片地面。",
            steps = listOf(
                "先看脚下有没有留一大片空地",
                "有就把镜头往下压，让脚贴住底边",
                "同时保证头顶还留出一点空间"
            ),
            params = listOf(
                "脚位置" to "画面底边",
                "头顶留白" to "约1/5",
                "机位" to "腰腹高度"
            ),
            why = "脚底到画框之间那段空地不产生任何信息，还会把人的整体比例往下压。脚踩到底边，腿的长度就完整地铺满画面下半部分，再配合低机位，人自然就显高了。",
            mistakes = listOf(
                "脚下留了半米空地，人显得又矮又小",
                "只顾脚贴边，把头顶切掉了"
            ),
            check = "看脚是不是正好贴着底边，头顶还有空隙就对了。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.PORTRAIT),
                weight = 15
            )
        ),
        Technique(
            id = "portrait_half_body",
            title = "拍半身别拍全身，表情才清楚",
            category = TechCategory.PORTRAIT,
            oneLiner = "退到膝盖以上就停",
            whenTo = "拍人像、想突出表情和状态时用。退到刚好能拍到膝盖以上就停下，不要再往后退，让上半身占满画面。",
            steps = listOf(
                "先看人在画面里占多大，太小就往近走",
                "退到膝盖以上刚好进画框就停下",
                "眼睛放在上三分线附近，头顶留一点空"
            ),
            params = listOf(
                "取景范围" to "膝盖以上",
                "人物占比" to "约2/3画面",
                "眼睛" to "上三分线"
            ),
            why = "全身照里脸只占很小一块，表情、眼神、皮肤质感全都看不出来。半身让人占满画面，观众能看清脸，人物和镜头之间也产生了交流感，这是人像最耐看的取景范围。",
            mistakes = listOf(
                "退老远拍个小全身，脸糊成一个点",
                "只拍到大头，看不到肩颈线条"
            ),
            check = "看脸在画面里占多大，能一眼看清表情就对了。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.PORTRAIT),
                minSkinRatio = 0.05f,
                weight = 20
            )
        ),
        Technique(
            id = "portrait_side_45_body",
            title = "侧身四十五度，正面站着显宽",
            category = TechCategory.PORTRAIT,
            oneLiner = "侧过身，只把脸转回来",
            whenTo = "拍人像、被拍的人正面站得笔直时用。让 TA 侧过身约四十五度，只把脸转回镜头，两只肩膀自然一前一后。",
            steps = listOf(
                "先看 TA 是不是正面平对着镜头",
                "让 TA 侧身四十五度，一只脚往前放",
                "只把脸转回来，肩膀保持一前一后"
            ),
            params = listOf(
                "身体角度" to "45度",
                "脸" to "转回镜头",
                "肩" to "一前一后"
            ),
            why = "正面站立时，身体最宽的部分正对镜头，肩宽和腰宽全被拍进去。侧到四十五度，身体在画面里的投影变窄，下颌线也因为转头露出来，人看起来瘦一圈还有立体感。",
            mistakes = listOf(
                "身体正对镜头站得笔直，显宽",
                "侧得太多变成纯侧脸，只看见耳朵"
            ),
            check = "看两只肩膀，是一前一后不重叠就对了；完全重叠说明侧得太多。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.PORTRAIT),
                minSkinRatio = 0.05f,
                weight = 20
            )
        ),
        Technique(
            id = "portrait_phone_slightly_high",
            title = "手机略高于眼睛，微俯十五度",
            category = TechCategory.PORTRAIT,
            oneLiner = "抬高一掌，微微往下压",
            whenTo = "拍人像、正面构图显得脸平脸大时用。把手机抬到比被拍者眼睛高一掌左右，镜头微微向下压，不要从下往上仰拍。",
            steps = listOf(
                "先看手机是不是和眼睛齐平或者更低",
                "抬到比眼睛高一掌，镜头微微往下压",
                "让 TA 下巴收一点，眼睛往上看镜头"
            ),
            params = listOf(
                "高度" to "高过眼睛约一掌",
                "俯角" to "约15度",
                "下巴" to "微收"
            ),
            why = "从下往上拍时，镜头离下巴近、离额头远，下巴和鼻孔被放大，脸型最难看。略微抬高再微俯，额头稍微变大、下巴收紧，接近人眼日常看人的角度，比例最舒服。",
            mistakes = listOf(
                "蹲着从下往上仰拍，双下巴全出来",
                "举太高变成大俯拍，头大脸小"
            ),
            check = "看下巴和额头的比例，接近你平视对方时的感觉就对了。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.PORTRAIT),
                minSkinRatio = 0.05f,
                weight = 20
            )
        ),
        Technique(
            id = "portrait_chin_down",
            title = "让 TA 收一点下巴，脸能小一圈",
            category = TechCategory.PORTRAIT,
            oneLiner = "按快门前说一句收下巴",
            whenTo = "拍人像、被拍的人习惯性仰头时用。按快门前提醒一句收下巴，同时让 TA 微微往前伸一点脖子再收回来。",
            steps = listOf(
                "先看 TA 的下巴是不是往上抬着",
                "说一句收一点下巴，别低头太多",
                "让 TA 脖子往前伸一点再收回，下颌线更紧"
            ),
            params = listOf(
                "下巴" to "微收",
                "脖子" to "略前伸后回收",
                "眼睛" to "平视镜头"
            ),
            why = "人一仰头，下颌到脖子之间那块软组织就被拉平摊开，堆成一层。收一点下巴，颈前肌肉收紧，下颌线变成一条清楚的折线，脸在画面里也跟着变小。",
            mistakes = listOf(
                "仰着头拍，双下巴和鼻孔都出来了",
                "收太多变成低头，眼睛全是眼白"
            ),
            check = "看下颌线是一条清楚折线就对了；糊成一片说明下巴没收住。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.PORTRAIT),
                minSkinRatio = 0.06f,
                weight = 19
            )
        ),
        Technique(
            id = "portrait_catch_motion",
            title = "抓转身那一下，比摆拍自然",
            category = TechCategory.PORTRAIT,
            oneLiner = "喊一句转身看我",
            whenTo = "拍人像、对方一站到镜头前就僵住时用。别让 TA 定住数三二一，改成喊一句转身看我，在动作过程中连拍。",
            steps = listOf(
                "先让 TA 背过身或者低头，别对着镜头准备",
                "喊一句转身看我，同时按住连拍",
                "从连拍里挑一张动作中间、头发还在动的"
            ),
            params = listOf(
                "张数" to "连拍5张以上",
                "触发" to "动作指令",
                "挑选" to "动作中间帧"
            ),
            why = "人一旦知道要拍了，就会下意识摆出一个表情，那个表情是演出来的，肩膀和嘴角都是紧的。动作过程中的中间帧来不及控制，表情和姿态都是真的，看起来才自然。",
            mistakes = listOf(
                "让 TA 定住不动，倒数三二一",
                "只按一张，正好卡在眨眼或者张嘴"
            ),
            check = "看肩膀是不是放松的、表情有没有做作的痕迹。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.PORTRAIT),
                minSkinRatio = 0.05f,
                weight = 19
            )
        ),
        Technique(
            id = "portrait_group_low_wide",
            title = "合影蹲低用广角，人人都是长腿",
            category = TechCategory.PORTRAIT,
            oneLiner = "蹲下来切超广角拍合影",
            whenTo = "拍三人以上的合影时用。蹲下来或者把手机放到腰部以下，切到超广角，从下往上拍，让人物的脚贴近画面底边。",
            steps = listOf(
                "先看合影是不是站成一字排开",
                "蹲下切超广角，让人往画面中间靠",
                "高的人站后排中间，矮的站前排"
            ),
            params = listOf(
                "倍率" to "0.5x",
                "机位" to "腰部以下",
                "排列" to "高的在后中间"
            ),
            why = "低机位加超广角，腿离镜头更近、在画面里占的像素更多，一排人的腿都会被拉长。同时广角在边缘拉伸明显，把人都往中间收，能避开两侧变形。",
            mistakes = listOf(
                "站直平拍合影，人人五五身",
                "把最边上的人放在画面边缘，拉得变形"
            ),
            check = "看所有人的腿是不是都比平时长，边缘的人有没有被拉歪。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.PORTRAIT),
                minSkinRatio = 0.08f,
                weight = 19
            )
        ),
        Technique(
            id = "portrait_negative_space",
            title = "留一片空给天空，人才不挤",
            category = TechCategory.PORTRAIT,
            oneLiner = "人靠边站，留大片空",
            whenTo = "拍情绪人像、或者背景只有天空和纯色墙时用。让人物站到画面一侧，把大半画面留给天空、墙面或者海面。",
            steps = listOf(
                "先看画面是不是被人塞满了",
                "让人往一侧站，留出大片空",
                "空的朝向要和人物视线或身体朝向一致"
            ),
            params = listOf(
                "人物占比" to "约1/3画面",
                "留白方向" to "顺着视线",
                "背景" to "天空或纯色墙"
            ),
            why = "空的地方不是浪费，是给眼睛休息和想象的余地。人物缩到一侧，画面就有了轻重，情绪才出得来；但空白的方向要和人物朝向一致，否则会像被人从背后拉住。",
            mistakes = listOf(
                "人物塞满整个画面，四边都挤",
                "留白留在人物背后，反方向堵住"
            ),
            check = "缩小看整张图，第一眼是人，第二眼是那片空就对了。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.PORTRAIT, SceneType.LANDSCAPE),
                weight = 15
            )
        ),
        Technique(
            id = "portrait_diagonal_body",
            title = "人沿着对角线躺，显长又显瘦",
            category = TechCategory.PORTRAIT,
            oneLiner = "让身体走一条对角线",
            whenTo = "拍坐在台阶上、躺在地上、或者拍全身时用。让人从头到脚沿着画面的一条对角线方向展开，别竖直站着。",
            steps = listOf(
                "先看人的身体走向是不是竖直的",
                "让 TA 顺着对角线方向坐或者躺",
                "脚伸向一个角，头朝另一个角"
            ),
            params = listOf(
                "角度" to "对角线方向",
                "构图" to "头脚各占一角",
                "留白" to "对角线两侧"
            ),
            why = "画面里对角线是最长的一条线，身体沿着它展开，同样的身高能占到更多像素。同时斜线比竖线更有动势，画面不呆板，人也看着修长。",
            mistakes = listOf(
                "人直立站在画面正中，压矮一截",
                "身体和画框边平行，看着僵硬"
            ),
            check = "看人的身体是不是斜着穿过画面，头脚两个角都会被用到。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.PORTRAIT),
                weight = 15
            )
        )
    )

    // ── 批次 B：STREET 2 条 ────────────────────────────────────────────────────

    private val STREET: List<Technique> = listOf(
        Technique(
            id = "street_vertical_lines",
            title = "建筑竖边要拍直，别凭手感拍",
            category = TechCategory.STREET,
            oneLiner = "让楼边和竖网格线重合",
            whenTo = "拍楼房、拍街道两侧的建筑时用。打开网格，找一条明显的建筑竖边，让它和某一条竖网格线完全重合。",
            steps = listOf(
                "先看画面里有没有明显的建筑竖边",
                "打开网格，让这条边和竖线重合",
                "身体前后微调，直到边完全平行"
            ),
            params = listOf(
                "网格" to "开",
                "对齐" to "竖网格线",
                "姿态" to "手机不要前后仰"
            ),
            why = "建筑是唯一带有绝对垂直参照的东西，人眼对它歪不歪极其敏感，一条边歪两度整张照片就废了。手机前后仰还会产生透视收缩，所以除了对齐，还要保持机身竖直。",
            mistakes = listOf(
                "凭手感端着拍，楼是斜的",
                "手机往后仰，楼往里倒"
            ),
            check = "看建筑竖边和画框边是不是平行的，平行就对了。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.LANDSCAPE, SceneType.GENERAL),
                weight = 17
            )
        ),
        Technique(
            id = "street_doorway_frame",
            title = "用门框窗框做画框，挡掉杂物",
            category = TechCategory.STREET,
            oneLiner = "退一步，把人套进框里",
            whenTo = "在街上看到门洞、窗框、拱廊时用。退后一步，让这些框成为画面的外框，把主体和杂乱的环境隔开。",
            steps = listOf(
                "先找一个门洞、窗框或者拱门",
                "退后一步，让框的边缘进入画面四边",
                "把主体放在框内，别压到框上"
            ),
            params = listOf(
                "框占比" to "画面四周约1/5",
                "主体位置" to "框内三分点",
                "机位" to "正对框心"
            ),
            why = "框住的视野把观众的注意力锁在框内，框外的杂物自动被忽略。同时框提供了前后两层空间，画面一下就有了纵深，比直接对着主体拍高级得多。",
            mistakes = listOf(
                "直接怼着主体拍，背景全乱",
                "框没对齐，一边粗一边细看着别扭"
            ),
            check = "看画面四边有没有框，框里的主体是不是第一眼就看见。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.GENERAL),
                weight = 16
            )
        )
    )

    // ── 批次 B：LANDSCAPE 4 条 ─────────────────────────────────────────────────

    private val LANDSCAPE: List<Technique> = listOf(
        Technique(
            id = "landscape_horizon_level",
            title = "地平线一定端平，歪一点就废",
            category = TechCategory.LANDSCAPE,
            oneLiner = "打开网格，把地平线压平",
            whenTo = "画面里出现海平面、地平线、栏杆这类横向长线时用。打开网格，把这条线和最上面或最下面的横线对齐，别让它歪着横穿画面。",
            steps = listOf(
                "先看画面里有没有一条横向长线",
                "打开网格，把这条线压到横网格线上",
                "慢慢左右转身体，直到两边同时贴住"
            ),
            params = listOf(
                "网格" to "开",
                "对齐" to "上或下三分线",
                "倾斜" to "0度"
            ),
            why = "水平线是画面的地基，人对它的歪斜极其敏感，歪一到两度整张照片就有说不出的别扭。而手持时身体本身就有轻微倾斜，靠眼睛判断不准，只有网格线能给出确定的参照。",
            mistakes = listOf(
                "凭手感端着拍，海平面是斜的",
                "只对了一边，另一边翘起来"
            ),
            check = "看地平线和画框上下边是不是平行，一眼就能看出来。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.LANDSCAPE),
                weight = 30
            )
        ),
        Technique(
            id = "landscape_ultrawide_center",
            title = "超广角装大场面，人别放边上",
            category = TechCategory.LANDSCAPE,
            oneLiner = "切0.5x，主体往中间放",
            whenTo = "拍大场景、想把整片风景和高楼一起收进来时用。切到超广角，同时把最重要的主体往画面中间挪，别放在边缘。",
            steps = listOf(
                "先退到退无可退，再考虑切超广角",
                "切到0.5x，把主体挪到画面中间",
                "手机保持水平，别往下压太多"
            ),
            params = listOf(
                "倍率" to "0.5x",
                "主体位置" to "画面中间1/3",
                "姿态" to "保持水平"
            ),
            why = "超广角在画面边缘的拉伸最严重，直线会被拉弯，人脸会被拉宽。把主体放在中间三分之一，变形最小；边缘留给风景和线条，反而能强化纵深感。",
            mistakes = listOf(
                "用0.5x把主体怼到边缘，拉得变形",
                "站得太近，画面全是畸变"
            ),
            check = "看边缘的直线有没有明显弯曲，主体是不是还保持原本比例。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.LANDSCAPE),
                weight = 20
            )
        ),
        Technique(
            id = "landscape_low_ground",
            title = "蹲下来贴地拍，纵深就出来了",
            category = TechCategory.LANDSCAPE,
            oneLiner = "手机贴地，往上抬一点",
            whenTo = "拍路面、街道、草地、沙滩这类有前景延展的场景时用。蹲下来把手机放到接近地面的位置，镜头略微上抬。",
            steps = listOf(
                "先站着拍一张，看是不是很平",
                "蹲下把手机放到贴地高度",
                "镜头略抬，让前景铺满画面下半部分"
            ),
            params = listOf(
                "机位" to "贴地10~20cm",
                "仰角" to "5~10度",
                "前景" to "占下半1/3"
            ),
            why = "站姿拍摄时，脚下的地面几乎不进入画面，缺少前景，画面就是一张平面的墙。贴地之后地面铺开成一条通道，视线顺着它走向远处，纵深感一下就出来了。",
            mistakes = listOf(
                "全程站着齐胸高度拍，画面很平",
                "蹲太低全拍地面，天空没了"
            ),
            check = "看画面下半部分有没有前景铺开，有就说明机位够低了。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.LANDSCAPE),
                weight = 19
            )
        ),
        Technique(
            id = "landscape_reflection",
            title = "找水面拍倒影，一半实一半虚",
            category = TechCategory.LANDSCAPE,
            oneLiner = "蹲到水边，让倒影占一半",
            whenTo = "在水边、雨后的积水旁、玻璃幕墙前时用。蹲低到接近水面，调整角度让倒影和实体各占画面一半左右。",
            steps = listOf(
                "先找一个有倒影的水面或玻璃面",
                "蹲到接近水面的高度",
                "调整角度，让实景和倒影对称展开"
            ),
            params = listOf(
                "机位" to "贴近水面",
                "分割" to "中间或下三分线",
                "对称" to "左右居中"
            ),
            why = "倒影是最稳的构图形式，它用一条水平线把画面切成镜像的两半，主体被复制了一次，信息量翻倍。而且倒影本身是虚的，和实体形成质感对比，比单一的实景更耐看。",
            mistakes = listOf(
                "站太高，倒影只剩细窄一条",
                "水面有波纹，倒影碎成一片"
            ),
            check = "看倒影是不是清楚的，实景和倒影大致对称就对了。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.LANDSCAPE, SceneType.NIGHT),
                weight = 19
            )
        )
    )

    // ── 批次 B：NIGHT 3 条 ─────────────────────────────────────────────────────

    private val NIGHT: List<Technique> = listOf(
        Technique(
            id = "night_protect_highlights",
            title = "夜景把高光压下去，别去提亮",
            category = TechCategory.NIGHT,
            oneLiner = "EV往下拉半档再按",
            whenTo = "拍夜景、灯牌、霓虹、路灯时用。把曝光补偿往下压到负三分之一到负半档，让灯牌上的字能看清。",
            steps = listOf(
                "先看灯牌和灯箱是不是糊成一片白",
                "曝光补偿往下拉到-0.3到-0.5",
                "点一下灯牌锁定测光，再微调"
            ),
            params = listOf(
                "EV" to "-0.3~-0.5",
                "测光" to "点灯牌",
                "对焦" to "手动往远拉"
            ),
            why = "夜景里光源和人眼的亮度差极大，相机为了看清暗处会整体提亮，结果灯全爆成白块。往下压一点，暗部本来也没有多少信息可以救，保住高光里的字和颜色，画面才有夜景的样子。",
            mistakes = listOf(
                "觉得暗就去提亮，灯全爆成白块",
                "压得太狠，整个画面死黑"
            ),
            check = "看灯牌上的字还能不能认出来，能认出来就对了。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.NIGHT),
                minHighlightClip = 0.08f,
                weight = 29
            )
        ),
        Technique(
            id = "night_iso_ceiling",
            title = "夜景ISO别超1600，噪点难救",
            category = TechCategory.NIGHT,
            oneLiner = "感光度压在1600以内",
            whenTo = "用专业模式拍夜景、画面开始出现明显噪点时用。把ISO手动压在一千六以内，缺的光用放慢快门来补。",
            steps = listOf(
                "先看画面是不是已经开始发灰发脏",
                "手动把ISO压到400到1200之间",
                "缺的光用放慢快门补，别加ISO"
            ),
            params = listOf(
                "ISO" to "400~1200",
                "上限" to "1600",
                "策略" to "快门优先"
            ),
            why = "ISO就是把信号放大，暗处的信号和噪点会被一起放大。拉到三千二以上，画面会出现大片彩噪和涂抹感，后期降噪只能把细节一起抹平。宁可慢一点快门，也别硬拉感光度。",
            mistakes = listOf(
                "图省事把ISO拉到6400，画面全是颗粒",
                "只压ISO不补快门，画面死黑"
            ),
            check = "放大看画面暗部，没有明显的彩色斑点就对了。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.NIGHT),
                maxLuma = 0.2f,
                weight = 22
            )
        ),
        Technique(
            id = "night_slow_shutter",
            title = "夜景快门放到二十分之一秒",
            category = TechCategory.NIGHT,
            oneLiner = "快门1/20到1/30再按",
            whenTo = "夜景太暗、画面糊成一片黑时用。把快门放到二十分之一到三十分之一秒，同时找个支点把手机靠稳。",
            steps = listOf(
                "先看画面是不是暗到看不见细节",
                "快门放到1/20到1/30",
                "手肘靠住墙或栏杆，按快门时屏住呼吸"
            ),
            params = listOf(
                "快门" to "1/20~1/30",
                "支点" to "墙/栏杆/桌面",
                "ISO" to "400~1200"
            ),
            why = "夜景的光本来就少，快门太快进光量不够，画面全是黑。放到二十分之一秒，进光量多出好几倍，同时这个速度双手还能勉强端住，再慢就必须上三脚架了。",
            mistakes = listOf(
                "快门太快，画面一片死黑",
                "放到半秒还手持，全是抖影"
            ),
            check = "看画面够不够亮，同时放大看有没有拖影，两者兼顾就对了。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.NIGHT),
                maxLuma = 0.15f,
                weight = 22
            )
        )
    )

    // ── 批次 B：LIGHT 3 条 ─────────────────────────────────────────────────────

    private val LIGHT: List<Technique> = listOf(
        Technique(
            id = "light_avoid_noon",
            title = "躲开正午顶光，去阴影里拍",
            category = TechCategory.LIGHT,
            oneLiner = "中午别硬拍，挪到阴影里",
            whenTo = "上午十一点到下午三点、太阳几乎在头顶时用。别在硬光下硬拍，把人和主体挪到树荫、屋檐、廊下这些有遮挡的地方。",
            steps = listOf(
                "先看地上的影子是不是很短很硬",
                "把主体挪到树荫或屋檐下",
                "或者等太阳偏西再拍"
            ),
            params = listOf(
                "时段" to "避开11点到15点",
                "位置" to "树荫/屋檐/廊下",
                "替代" to "等日落前"
            ),
            why = "中午的太阳接近垂直，光从头顶压下来，在眼窝、鼻下、下巴留出又黑又硬的阴影，人脸像戴了面具。而阴影里的光是被环境反射的散射光，方向柔和，脸反而好看。",
            mistakes = listOf(
                "正午硬光直接拍脸，眼窝两团黑",
                "想靠后期提亮，阴影只会更脏"
            ),
            check = "看眼窝和鼻下有没有又黑又硬的界线，没有就对了。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.PORTRAIT),
                minLuma = 0.5f,
                weight = 20
            )
        ),
        Technique(
            id = "light_front_light_clear",
            title = "顺光拍清楚，颜色细节都在",
            category = TechCategory.LIGHT,
            oneLiner = "让光从你身后照过去",
            whenTo = "拍美食、花卉、商品这类需要看清细节的东西时用。转一下身，让光源在你背后，正对着主体照过去。",
            steps = listOf(
                "先看主体是不是背对着光",
                "转半圈，让光从你身后打向主体",
                "注意别让自己的影子落进画面"
            ),
            params = listOf(
                "光位" to "顺光",
                "角度" to "与镜头同向",
                "注意" to "避开自身投影"
            ),
            why = "顺光时主体每个表面都被均匀照亮，颜色最准、细节最全、对焦也最容易。虽然立体感弱，但拍清楚比拍立体更重要，尤其拍食物和商品，颜色对不对直接决定有没有食欲。",
            mistakes = listOf(
                "逆着光拍菜，一片死黑",
                "自己的影子落在主体上"
            ),
            check = "看主体最暗的地方还有没有细节，有就说明光够。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.INDOOR, SceneType.GENERAL),
                minLuma = 0.35f,
                weight = 19
            )
        ),
        Technique(
            id = "light_overcast_soft",
            title = "阴天别浪费，那是天然柔光箱",
            category = TechCategory.LIGHT,
            oneLiner = "阴天出门拍，稍加曝光",
            whenTo = "阴天、多云、或者太阳被云挡住时用。别因为天阴就收工，这时候的光最适合拍人和静物，只需稍微提亮一点。",
            steps = listOf(
                "先看地上有没有清楚的影子，没有就是散射光",
                "这时候出门拍，人往空旷处站",
                "曝光补偿加三分之一档，把灰调提亮"
            ),
            params = listOf(
                "EV" to "+0.3",
                "光质" to "散射",
                "方向" to "任意"
            ),
            why = "云层把太阳变成一个巨大的柔光罩，光线从四面八方来，脸上没有硬阴影，皮肤细节和眼神光都很干净。这种光在影棚里要靠柔光箱才能做出来，唯一的问题只是整体偏灰。",
            mistakes = listOf(
                "觉得阴天不好看，干脆不拍了",
                "只加曝光不调对比，画面灰蒙蒙"
            ),
            check = "看脸上有没有硬阴影，没有且亮度正常就对了。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.PORTRAIT, SceneType.GENERAL),
                maxLuma = 0.5f,
                weight = 19
            )
        )
    )

    // ── 批次 B：FOOD 2 条 ──────────────────────────────────────────────────────

    private val FOOD: List<Technique> = listOf(
        Technique(
            id = "food_tele_compress",
            title = "走近用长焦拍，背景被压扁",
            category = TechCategory.FOOD,
            oneLiner = "走近两步，再切两倍",
            whenTo = "拍菜、背景比较乱或者桌子很宽时用。别站在原地用手指放大，先走近两步再切到两倍，让背景虚化得更自然。",
            steps = listOf(
                "先看背景是不是很杂乱",
                "走近两步，让菜占满画面",
                "切到两倍，背景自然被压缩"
            ),
            params = listOf(
                "倍率" to "2x~3x",
                "距离" to "约30~50cm",
                "角度" to "45度斜拍"
            ),
            why = "长焦的视角窄，背景里只留下菜后面一小块，杂乱的部分直接被裁出画面。同时长焦的透视压缩让前后物体的距离感变短，主体从背景里跳出来。但必须走近，站在原地的数码变焦只会放大噪点。",
            mistakes = listOf(
                "站在原地用手指放大，画面全是颗粒",
                "离太近，只拍到半个盘子"
            ),
            check = "看主体是不是清楚、背景是不是干净虚化就对了。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.INDOOR),
                weight = 20
            )
        ),
        Technique(
            id = "food_steam_backlight",
            title = "趁热逆光拍，热气才看得见",
            category = TechCategory.FOOD,
            oneLiner = "让光从菜后面来，趁热拍",
            whenTo = "拍汤、拍刚出锅的热菜时用。让窗户或灯落在菜的后面，趁热气还在往上冒的时候按下快门。",
            steps = listOf(
                "先看菜还在不在冒热气",
                "挪一下位置，让光从菜后面来",
                "对着热气对焦，趁浓的时候按"
            ),
            params = listOf(
                "光位" to "逆光或侧逆光",
                "时机" to "出锅3分钟内",
                "背景" to "深色更显热气"
            ),
            why = "热气本身是半透明的白色水汽，只有在逆光时才被照亮，顺光下它就是透明的什么都看不见。深色背景能把白色的热气衬出来，而菜一凉热气就真没了，所以时机比参数重要。",
            mistakes = listOf(
                "等菜凉了再拍，热气全没了",
                "用闪光灯直打，热气被冲散"
            ),
            check = "看画面里有没有一团白雾，有就说明抓到了。",
            trigger = TechTrigger(
                scenes = setOf(SceneType.BACKLIT),
                weight = 21
            )
        )
    )

    /** 全部 44 条，顺序按批次分组（自拍 → 运镜 → 参数 → 避坑 → 人像 → 街头 → 风光 → 夜景 → 用光 → 食物）。 */
    val list: List<Technique> = SELFIE + CAMERA_MOVE + PARAMS + PITFALL +
        PORTRAIT + STREET + LANDSCAPE + NIGHT + LIGHT + FOOD

    /**
     * 全库 83 条（39 老 + 44 新）。
     *
     * 这只是 [PhotographyBook.all] 的别名，**不是** `all + list` ——
     * 后者会把 44 条算两遍（83 → 127），而且 `id` 唯一性断言会立刻红。
     * 保留这个名字是因为调用点（`TechniqueMatcher`、测试）读起来更贴题。
     */
    val combined: List<Technique> by lazy { PhotographyBook.all }

    /** 按 id 索引，供 `PromptEngine` 的 `availableTipIds` 过滤与调试查表。 */
    val byId: Map<String, Technique> by lazy { combined.associateBy { it.id } }

    /**
     * **可以进推送池**的 id：全库减去仅浏览的 3 条与自拍的 5 条。
     *
     * 生产环境（`MainActivity` 构造 `PromptScheduler` 时）传这个集合，而不是 `byId.keys`。
     * 这样「禁推」就有两道闸：
     * 1. 规则表不点名它们（`PromptEngine` 侧）；
     * 2. 即使有人点名了，id 也不在调度器的可用白名单里，会被静默跳过（这里）。
     *
     * QA P2-2 指出的就是原来只有第一道闸 —— 不泄漏靠的是"恰好没有规则点名"。
     */
    val pushEligibleIds: Set<String> by lazy { byId.keys - BROWSE_ONLY_IDS - SELFIE_IDS }

    /** 44 条已经落地。调度器的技法过滤只在为 true 时启用。 */
    fun isReady(): Boolean = list.size == EXPECTED_COUNT

    /** 产品口径的条数。 */
    const val EXPECTED_COUNT = 44

    /** 分类分布，`TechniqueBookV15Test` 用它逐个对数。 */
    fun categoryCounts(): Map<TechCategory, Int> = list.groupingBy { it.category }.eachCount()
}
