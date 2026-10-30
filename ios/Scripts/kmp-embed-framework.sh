#!/bin/sh
# ios/Scripts/kmp-embed-framework.sh —— 把 Shared.framework 嵌进 .app/Frameworks。
#
# ★ 为什么要有这一步（embedAndSignAppleFrameworkForXcode 之外）：
#   1. KMP 的 framework 默认是**动态**的 → 必须进 .app/Frameworks，运行时才找得到
#      （靠 LD_RUNPATH_SEARCH_PATHS 里的 @executable_path/Frameworks 解析）；
#   2. **CI 一律不签名**：这种情况下 framework 与 App 都不该带签名，
#      所以这里的 codesign 是**条件执行**的 —— 有签名身份才签，没有就如实跳过；
#   3. 这一步放在 **postCompile**（Compile Sources 之后、App 本体签名之前）：
#      往**已签名**的 .app 里塞东西会让签名失效，顺序必须对。
#
# ★ 幂等：重复执行会覆盖同名目录，不会产生两份 framework。
#
# 用法：由 Xcode 的 Run Script 阶段调用（见 ios/project.yml 的 postCompileScripts）。
set -u

FRAMEWORK_NAME="Shared"

log() { echo "[ShotTuner/KMP] ${*}"; }
warn() { echo "::warning::${*}"; }
fail() {
    echo "::error::${*}"
    exit 1
}

# ★ framework 的落盘目录名用的是 **SDK_NAME**（如 iphonesimulator17.5），
#   不是 PLATFORM_NAME（iphonesimulator）—— KGP 的 frameworkSearchDir = File(CONFIGURATION, SDK_NAME)。
#   实测踩过：用 PLATFORM_NAME 找 → 明明编出来了却说"找不到"，跳过嵌入，
#   结果真机上 "Library not loaded"。所以两条都试。
SRC=""
for PLATFORM_KEY in "${SDK_NAME:-}" "${PLATFORM_NAME:-iphonesimulator}"; do
    [ -n "${PLATFORM_KEY}" ] || continue
    CANDIDATE="${SRCROOT}/../shared/build/xcode-frameworks/${CONFIGURATION:-Debug}/${PLATFORM_KEY}/${FRAMEWORK_NAME}.framework"
    if [ -d "${CANDIDATE}" ]; then
        SRC="${CANDIDATE}"
        break
    fi
done

if [ -z "${SRC}" ]; then
    # 不在这里失败：App 的编译与链接不依赖嵌入（链接用的是 FRAMEWORK_SEARCH_PATHS）。
    # 但一定要喊出来 —— 否则真机装上去会报「Library not loaded」。
    warn "找不到 ${FRAMEWORK_NAME}.framework（试过 SDK_NAME=${SDK_NAME:-未设置} 与 PLATFORM_NAME=${PLATFORM_NAME:-未设置}）—— 跳过嵌入。真机运行时会出现「Library not loaded」"
    log "KMP_FRAMEWORK_EMBEDDED=0"
    exit 0
fi

# .app 的位置：${BUILT_PRODUCTS_DIR}/<Product>.app/<FRAMEWORKS_FOLDER_PATH>
APP_BUNDLE="${BUILT_PRODUCTS_DIR:-}/$(FULL_PRODUCT_NAME:-ShotTuner.app)"
DEST_DIR="${APP_BUNDLE}/${FRAMEWORKS_FOLDER_PATH:-Frameworks}"
DEST="${DEST_DIR}/${FRAMEWORK_NAME}.framework"

log "嵌入 framework：${SRC} → ${DEST}"
mkdir -p "${DEST_DIR}" || fail "无法创建 ${DEST_DIR}"
rm -rf "${DEST}"
cp -R "${SRC}" "${DEST}" || fail "拷贝 framework 失败"

# ── 签名：只在真的需要签的时候才签 ─────────────────────────────────────────
# CI：CODE_SIGNING_ALLOWED=NO → EXPANDED_CODE_SIGN_IDENTITY 为空 → 跳过。
# 本机侧载：签名在设备端用侧载工具完成（工程本身始终不签名）。
IDENTITY="${EXPANDED_CODE_SIGN_IDENTITY:-}"
if [ "${CODE_SIGNING_ALLOWED:-NO}" = "NO" ] || [ -z "${IDENTITY}" ]; then
    log "未签名模式（CI 一律不签名）→ 跳过 codesign"
else
    log "签名 framework：${IDENTITY}"
    codesign --force --sign "${IDENTITY}" "${DEST}" || fail "framework 签名失败：${DEST}"
fi

log "KMP_FRAMEWORK_EMBEDDED=1"
