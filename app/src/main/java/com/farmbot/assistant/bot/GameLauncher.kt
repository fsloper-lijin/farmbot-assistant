package com.farmbot.assistant.bot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.farmbot.assistant.core.IO

/**
 * 拉起王者荣耀。
 *
 * 实测经验：`getLaunchIntentForPackage` 在这台机器上拉不起来，
 * 必须用显式 ComponentName 指定 SGameActivity，否则调用无声失败。
 */
object GameLauncher {

    const val PACKAGE = "com.tencent.tmgp.sgame"
    private const val ACTIVITY = "com.tencent.tmgp.sgame.SGameActivity"
    private const val TAG = "Launch"

    fun launch(ctx: Context): Boolean {
        val explicit = runCatching {
            ctx.startActivity(
                Intent().apply {
                    component = ComponentName(PACKAGE, ACTIVITY)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }
            )
            true
        }.getOrElse { false }

        if (explicit) {
            IO.log(ctx, TAG, "显式启动成功")
            return true
        }

        val fallback = runCatching {
            ctx.packageManager.getLaunchIntentForPackage(PACKAGE)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(it)
                true
            } ?: false
        }.getOrDefault(false)

        IO.log(ctx, TAG, "显式=$explicit 兜底=$fallback")
        return fallback
    }
}
