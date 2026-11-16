package com.farmbot.assistant.bot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.farmbot.assistant.R
import com.farmbot.assistant.core.IO
import com.farmbot.assistant.perm.Perms

/**
 * 「农场保活」快捷开关磁贴（参考 GKD 的做法）。
 *
 * 作用：无障碍服务在 MIUI/HyperOS 上经常被系统回收。把服务挂到一个系统级快捷开关上，
 * 可以让 SystemUI 一直持有这个组件，显著降低被回收的概率；用户也能在下拉面板里
 * 一眼看到服务是否还活着，并在原地把它重新拉起来。
 *
 * 本类目前只做「磁贴本体 + 状态回显 + 跳转引导」，真正的保活/自愈逻辑待后续实现。
 */
class KeepAliveTile : TileService() {

    override fun onTileAdded() {
        super.onTileAdded()
        Perms.setTilePresent(this, true)
        IO.log(this, TAG, "tile added")
        render()
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        Perms.setTilePresent(this, false)
        IO.log(this, TAG, "tile removed")
    }

    override fun onStartListening() {
        super.onStartListening()
        // 能被 SystemUI 绑定说明磁贴确实在面板里；重装/重启后靠这里补回状态
        Perms.setTilePresent(this, true)
        render()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val alive = BotService.isReady
        IO.log(this, TAG, "tile clicked ready=$alive")
        if (alive) {
            Toast.makeText(this, "农场保活已就绪", Toast.LENGTH_SHORT).show()
        } else {
            // TODO: 真正的保活动作（自动重新拉起无障碍）待实现，先引导用户去设置页
            Toast.makeText(this, "无障碍服务未连接，正在打开设置页", Toast.LENGTH_SHORT).show()
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val alive = BotService.isReady
        tile.state = if (alive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "农场保活"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        runCatching { tile.subtitle = if (alive) "服务运行中" else "服务未连接" }
        tile.updateTile()
    }

    companion object {
        private const val TAG = "Tile"

        fun component(ctx: Context): ComponentName =
            ComponentName(ctx.packageName, KeepAliveTile::class.java.name)

        /** 供 Perms 判断磁贴是否已加入面板时使用 */
        fun shortComponent(ctx: Context): String =
            "${ctx.packageName}/.bot.KeepAliveTile"
    }
}
