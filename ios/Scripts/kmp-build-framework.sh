#!/bin/sh
# ios/Scripts/kmp-build-framework.sh —— 产出 Kotlin/Native 的 Shared.framework。
#
# ★ 挂在 Xcode 的 **preBuild** 阶段（早于 Compile Sources）：
#   链接器要在链接前就找得到 Shared.framework，所以产出必须发生在这里。
#
# ★ 为什么首选 embedAndSignAppleFrameworkForXcode（KMP 集成方案 B）：
#   它是 KGP 的官方入口，会按 Xcode 当前的环境变量（CONFIGURATION / PLATFORM_NAME / ARCHS）
#   自动挑对应的 Kotlin 目标，并把 framework 放到约定目录。
#   方案 B 的好处：整条依赖管理链路**只有 Gradle 一条**（D1 之后没有 CocoaPods、没有 SPM），
#   出问题在 xcodebuild 日志里能直接看到 gradle 输出，好定位。
#
# ★ 为什么它失败不直接报错（而是回退到显式 link 任务）：
#   **CI 一律不签名**（硬规则一：CODE_SIGNING_ALLOWED=NO），此时展开的签名身份是空串，
#   embedAndSign 内部那一步签名会失败。若因此让整个 Xcode 构建挂掉，
#   就等于「CI 不签名」这条硬规则把 iOS 编译门禁也一起废掉了。
#   所以这里：失败 → 记警告 → 回退到显式 link 任务（产出同一个 framework，只是不签名），
#   再由 kmp-embed-framework.sh 把**未签名**的 framework 嵌进 .app。
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
        # gradlew 在 Windows 上 checkout 出来可能是 644，Linux/macOS 上没有执行位
        sh "$REPO_ROOT/gradlew" "$@" --console=plain
    fi
}

log "开始产出 framework（target=$KMP_TARGET variant=$KMP_VARIANT configuration=${CONFIGURATION:-Debug} platform=${PLATFORM_NAME:-iphonesimulator}）"

if run_gradle ":shared:embedAndSignAppleFrameworkForXcode" $GRADLE_FLAGS; then
    log "embedAndSignAppleFrameworkForXcode 成功"
else
    log "embedAndSignAppleFrameworkForXcode 失败（CI 不签名时它的签名步骤会失败）→ 回退到显式 link 任务"
    run_gradle ":shared:$LINK_TASK" $GRADLE_FLAGS || fail "link 任务 $LINK_TASK 失败"
fi

# ── 5) 确保 framework 在约定的位置 ─────────────────────────────────────────
#   链接器与 Swift 的模块搜索都靠 FRAMEWORK_SEARCH_PATHS 指向这个目录。
CANONICAL_DIR="$REPO_ROOT/shared/build/xcode-frameworks/${CONFIGURATION:-Debug}/${PLATFORM_NAME:-iphonesimulator}"
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
