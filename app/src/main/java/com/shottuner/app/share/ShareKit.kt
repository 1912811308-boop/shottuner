package com.shottuner.app.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * 一键系统分享（G2-B）。
 *
 * 走 `ACTION_SEND` + `FileProvider`，把带水印的成片交给微信 / 朋友圈 / 小红书 / 蓝牙。
 * **零网络、零新增权限**：分享是系统 `Chooser` 的事，我们不申请任何东西，
 * 也不引入任何第三方 SDK（manifest 里 INTERNET / ACCESS_NETWORK_STATE 是被
 * `tools:node="remove"` 删掉的，这条立身之本不能破）。
 *
 * 两个入口：
 * 1. 相册里的成片 —— `uri` 已经是 `content://media/...`，直接分享；
 * 2. 相册写入失败时的降级路径 —— 文件还在 `cacheDir`，必须过 [uriForCacheFile]
 *    包成 `content://` 才能给别的应用读（`file://` 在 Android 7+ 会被接收方拒绝）。
 */
object ShareKit {

    /**
     * 把 [uri] 交给系统分享面板。
     *
     * @return 成功拉起面板 true；`uri` 为 null 或没有任何应用能接收图片时 false
     */
    fun share(context: Context, uri: Uri?): Boolean {
        if (uri == null) return false
        return try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = MIME_JPEG
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // 部分接收方（微信 / 部分 ROM 的相册）只读 ClipData，不读 EXTRA_STREAM
                clipData = ClipData.newRawUri("shottuner_shot", uri)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            // 逐个候选授予读权限：Chooser 只保证把 flag 传给最终被选中的那一个，
            // 而部分 ROM 的 Chooser 实现会丢 flag，显式授权才能避免接收方报
            // "Permission Denial: reading Uri"。
            grantToCandidates(context, send, uri)

            val chooser = Intent.createChooser(send, CHOOSER_TITLE)
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 缓存文件 → `content://` Uri。
     *
     * FileProvider 的 authority 用 `${applicationId}.fileprovider`（与 manifest 一致），
     * 这里用 `context.packageName` 拼，debug 包的 `.debug` 后缀也能对上。
     *
     * @return 文件不存在或 FileProvider 未配置时 null
     */
    fun uriForCacheFile(context: Context, file: File?): Uri? {
        if (file == null || !file.exists() || file.length() <= 0L) return null
        return try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (t: Throwable) {
            null
        }
    }

    private fun grantToCandidates(context: Context, send: Intent, uri: Uri) {
        try {
            val flag = Intent.FLAG_GRANT_READ_URI_PERMISSION
            val candidates = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentActivities(
                    send,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentActivities(send, PackageManager.MATCH_DEFAULT_ONLY)
            }
            for (info in candidates) {
                val pkg = info.activityInfo?.packageName ?: continue
                context.grantUriPermission(pkg, uri, flag)
            }
        } catch (t: Throwable) {
            // 授权失败不致命：FLAG_GRANT_READ_URI_PERMISSION 仍在 intent 上，
            // 大多数接收方靠它就够了。
        }
    }

    private const val MIME_JPEG = "image/jpeg"
    private const val CHOOSER_TITLE = "分享这张照片"
}
