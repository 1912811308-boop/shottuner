#!/bin/sh
# ios/Scripts/kmp-build-framework.sh —— 产出 Kotlin/Native 的 Shared.framework。
#
# ★ 挂在 Xcode 的 **preBuild** 阶段（早于 Compile Sources）：
#   链接器要在链接前就找得到 Shared.framework，所以产出必须发生在这里。
#
# ★ 为什么首选 embedAndSign…AppleFrameworkForXcode（KMP 集成方案 B）：
#   它是 KGP 的官方入口，会按 Xcode 当前的环境变量（CONFIGURATION / PLATFORM_NAME /
#   SDK_NAME / ARCHS / TARGET_BUILD_DIR / FRAMEWORKS_FOLDER_PATH）自动挑对应的
#   Kotlin 目标，并把 framework 拷进 .app/Frameworks。
#   方案 B 的好处：整条依赖管理链路**只有 Gradle 一条**（D1 之后没有 CocoaPods、没有 SPM），
#   出问题在 xcodebuild 日志里能直接看到 gradle 输出，好定位。
#
# ★ 为什么它失败不直接报错（而是回退到显式 link 任务）：
#   回退路径产出的是同一个 framework（只是不签名），
#   再由 kmp-embed-framework.sh 负责嵌入。这样「embedAndSign 在某种环境下失败」
#   不会让整个 iOS 编译门禁变红 —— **红一次的门禁等于没有门禁**。
#
# 用法：由 Xcode 的 Run Script 阶段调用（见 ios/project.yml 的 preBuildScripts）。
#      也可手工执行做本地排查： sh ios/Scripts/kmp-build-framework.sh
set -u

FRAMEWORK_NAME="Shared"

log() { echo "[ShotTuner/KMP] $*"; }
fail() {
    echo "::error::$*"
    exit 1
}

# ── 0) 仓库根目录 ──────────────────────────────────────────────────────────
# $SRCROOT = .xcodeproj 所在目录（ios/），仓库根在它上一级。
REPO_ROOT="$(cd "$SRCROOT/.." 2>/dev/null && pwd)"
[ -n "$REPO_ROOT" ] || fail "无法定位仓库根目录（SRCROOT=$SRCROOT）"
cd "$REPO_ROOT" || fail "无法进入仓库根目录：$REPO_ROOT"

# ── 1) JDK：Xcode 的脚本阶段不加载 shell 配置，java 常常不在 PATH 里 ────────
#   现象是 "Unable to locate a Java Runtime" —— 很容易被误判成 Gradle 的问题。
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
    if [ -x /usr/libexec/java_home ]; then
        JH="$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home 2>/dev/null || true)"
        if [ -n "$JH" ] && [ -x "$JH/bin/java" ]; then
            JAVA_HOME="$JH"
            export JAVA_HOME
        fi
    fi
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
    for CANDIDATE in \
        /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
        /usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
        /Library/Java/JavaVirtualMachines/*/Contents/Home
    do
        if [ -x "$CANDIDATE/bin/java" ]; then
            JAVA_HOME="$(cd "$CANDIDATE" && pwd)"
            export JAVA_HOME
            break
        fi
    done
fi
[ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ] \
    || fail "找不到 JDK —— KMP 集成需要 JDK 17+（CI 上由 actions/setup-java 提供）"
log "JAVA_HOME=$JAVA_HOME"

# ── 2) gradle.properties ───────────────────────────────────────────────────
#   仓库里的 gradle.properties 被 .gitignore 排除（里面有明文签名口令）。
#   没有它，Gradle 连配置阶段都过不去（:app 模块的 AGP 需要里面的 android.useAndroidX）。
if [ ! -f "$REPO_ROOT/gradle.properties" ]; then
    if [ -f "$REPO_ROOT/gradle.properties.example" ]; then
        cp "$REPO_ROOT/gradle.properties.example" "$REPO_ROOT/gradle.properties"
        log "gradle.properties 缺失 → 已从 gradle.properties.example 生成一份（里面的 RELEASE_* 是空的）"
    else
        fail "缺少 gradle.properties 且找不到 gradle.properties.example"
    fi
fi

# ── 3) 选 Gradle 侧的目标与变体 ────────────────────────────────────────────
#   ★ 任务名的拼写很容易错：模拟器目标拆成 iosSimulatorArm64（不是 iosSimulator）。
case "${PLATFORM_NAME:-iphonesimulator}" in
    iphoneos)
        KMP_TARGET="iosArm64"
        LINK_TASK_DEBUG="linkDebugFrameworkIosArm64"
        LINK_TASK_RELEASE="linkReleaseFrameworkIosArm64"
        ;;
    *)
        KMP_TARGET="iosSimulatorArm64"
        LINK_TASK_DEBUG="linkDebugFrameworkIosSimulatorArm64"
        LINK_TASK_RELEASE="linkReleaseFrameworkIosSimulatorArm64"
        ;;
esac

case "${CONFIGURATION:-Debug}" in
    Release*)
        KMP_VARIANT="releaseFramework"
        LINK_TASK="$LINK_TASK_RELEASE"
        ;;
    *)
        KMP_VARIANT="debugFramework"
        LINK_TASK="$LINK_TASK_DEBUG"
        ;;
esac

# ── 4) 跑 Gradle ───────────────────────────────────────────────────────────
#   ★ -PenableAppleTargets=true 是必须的：
#     shared/build.gradle.kts 里 iosArm64 / iosSimulatorArm64 由这个开关声明，
#     不开就没有 iOS 目标，任务根本不存在。
GRADLE_FLAGS="-PenableAppleTargets=true"

run_gradle() {
    if [ -x "$REPO_ROOT/gradlew" ]; then
        "$REPO_ROOT/gradlew" "$@" --console=plain
    else
        # gradlew 在 Windows 上 checkout 出来可能是 644，Linux/macOS 上没有执行权限
        sh "$REPO_ROOT/gradlew" "$@" --console=plain
    fi
}

log "开始产出 framework（target=$KMP_TARGET variant=$KMP_VARIANT configuration=${CONFIGURATION:-Debug} platform=${PLATFORM_NAME:-iphonesimulator}）"

# ★ embedAndSign 的任务名**不写死**，而是问 Gradle 要任务列表：
#   KGP 用 lowerCamelCaseName("embedAndSign", <framework 名>, "AppleFrameworkForXcode")
#   拼出来的 —— 本工程实测拿到的名字是 `embedAndSignAppleFrameworkForXcode`
#   （<framework 名> 那段是空的），但它**依赖 baseName**，改名就会变。
#   问 Gradle 比猜靠谱：找不到就是真的没有（比如没声明 binaries.framework）。
#
#   ★ 优先挑 embedAndSign 那个（它既打包到 xcode-frameworks/ 又嵌入 .app）；
#     没有才退化到 assemble 那个（只负责打包）——
#     嵌入这一步由 kmp-embed-framework.sh 兜底，两条路都不会漏。
TASK_LIST="$(run_gradle ":shared:tasks" --all $GRADLE_FLAGS 2>/dev/null)"
EMBED_TASKS="$(printf '%s\n' "$TASK_LIST" \
    | grep -oE '[A-Za-z0-9_]*embedAndSign[A-Za-z0-9_]*AppleFrameworkForXcode' \
    | sort -u)"
if [ -z "$EMBED_TASKS" ]; then
    EMBED_TASKS="$(printf '%s\n' "$TASK_LIST" \
        | grep -oE '[A-Za-z0-9_]*assemble[A-Za-z0-9_]*AppleFrameworkForXcode' \
        | sort -u)"
fi

if [ -z "$EMBED_TASKS" ]; then
    fail "shared 模块没有 Apple framework 任务 —— 检查 shared/build.gradle.kts 里有没有声明 binaries.framework { baseName = \"$FRAMEWORK_NAME\" }"
fi

EMBED_OK=0
for CANDIDATE in $EMBED_TASKS; do
    log "尝试 embedAndSign 任务：$CANDIDATE"
    if run_gradle ":shared:$CANDIDATE" $GRADLE_FLAGS; then
        log "embedAndSign 任务 $CANDIDATE 执行成功"
        EMBED_OK=1
        break
    fi
    log "embedAndSign 任务 $CANDIDATE 失败"
done

if [ "$EMBED_OK" -ne 1 ]; then
    # 回退：显式 link 任务产出的是同一个 framework，只是不做嵌入/签名那一步。
    #   嵌入由 kmp-embed-framework.sh 负责（那里会按「有没有签名身份」决定要不要 codesign）。
    log "embedAndSign 全部失败 → 回退到显式 link 任务 $LINK_TASK"
    run_gradle ":shared:$LINK_TASK" $GRADLE_FLAGS || fail "link 任务 $LINK_TASK 失败"
fi

# ── 5) 确保 framework 在约定的位置 ─────────────────────────────────────────
#   链接器与 Swift 的模块搜索都靠 FRAMEWORK_SEARCH_PATHS 指向这个目录。
#   ★ KGP 那边用的是 SDK_NAME，这里两个都试（真机/模拟器上它们取值相同）。
CANONICAL_DIR=""
for PLATFORM_KEY in "${PLATFORM_NAME:-iphonesimulator}" "${SDK_NAME:-iphonesimulator}"; do
    if [ -d "$REPO_ROOT/shared/build/xcode-frameworks/${CONFIGURATION:-Debug}/$PLATFORM_KEY/$FRAMEWORK_NAME.framework" ]; then
        CANONICAL_DIR="$REPO_ROOT/shared/build/xcode-frameworks/${CONFIGURATION:-Debug}/$PLATFORM_KEY"
        break
    fi
done
if [ -z "$CANONICAL_DIR" ]; then
    CANONICAL_DIR="$REPO_ROOT/shared/build/xcode-frameworks/${CONFIGURATION:-Debug}/${PLATFORM_NAME:-iphonesimulator}"
fi
CANONICAL="$CANONICAL_DIR/$FRAMEWORK_NAME.framework"

if [ ! -d "$CANONICAL" ]; then
    # embedAndSign 有时会把产物放在别处（或失败前没走到拷贝那步）→ 从 Gradle 的输出目录补一次。
    for CANDIDATE in \
        "$REPO_ROOT/shared/build/bin/$KMP_TARGET/$KMP_VARIANT/$FRAMEWORK_NAME.framework" \
        "$REPO_ROOT/shared/build/xcode-frameworks/${CONFIGURATION:-Debug}/$FRAMEWORK_NAME.framework"
    do
        if [ -d "$CANDIDATE" ]; then
            log "framework 在 $CANDIDATE → 拷贝到约定目录 $CANONICAL_DIR"
            mkdir -p "$CANONICAL_DIR" || fail "无法创建 $CANONICAL_DIR"
            cp -R "$CANDIDATE" "$CANONICAL_DIR/" || fail "拷贝 framework 失败"
            break
        fi
    done
fi

[ -d "$CANONICAL" ] || fail "找不到 $FRAMEWORK_NAME.framework（target=$KMP_TARGET variant=$KMP_VARIANT）"
log "framework 就绪：$CANONICAL"
log "KMP_FRAMEWORK_READY=1"
