package com.farmbot.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.farmbot.assistant.ui.theme.Bg
import com.farmbot.assistant.ui.theme.Cyan
import com.farmbot.assistant.ui.theme.Line
import com.farmbot.assistant.ui.theme.Surface1
import com.farmbot.assistant.ui.theme.Surface2
import com.farmbot.assistant.ui.theme.TextPrimary
import com.farmbot.assistant.ui.theme.TextSecondary
import com.farmbot.assistant.ui.theme.TextTertiary
import com.farmbot.assistant.ui.theme.Warn

@Composable
fun PermissionScreen(
    accessibility: Boolean,
    /** 授权还在、服务却被系统解除绑定：重开开关无效，只能重启设备 */
    accessibilityStuck: Boolean,
    notifications: Boolean,
    overlay: Boolean,
    bgPopup: Boolean,
    alarm: Boolean,
    autoStart: Boolean,
    battery: Boolean,
    tile: Boolean,
    /** 测试闹钟剩余秒数，>0 表示正在等待触发 */
    alarmRemain: Long,
    onRefresh: () -> Unit,
    onAccessibility: () -> Unit,
    onNotification: () -> Unit,
    onOverlay: () -> Unit,
    onBgPopup: () -> Unit,
    onAlarm: () -> Unit,
    onAlarmTest: () -> Unit,
    onAlarmOpen: () -> Unit,
    onAutoStart: () -> Unit,
    onBattery: () -> Unit,
    onTile: () -> Unit,
    onEnter: () -> Unit,
) {
    val missing = listOf(accessibility, notifications, overlay).count { !it }
    val ready = missing == 0
    val wide = isWideScreen()
    val pad = if (wide) 28.dp else 18.dp

    val testing = alarmRemain > 0
    val entries = listOf(
        PermEntry(
            title = "无障碍服务",
            desc = if (accessibilityStuck)
                "授权还在，但系统已解除绑定。重开开关、重装都无效，重启平板才能恢复"
            else "截屏识别与模拟点击",
            ok = accessibility,
            // 不参与放行判断（账号管理还能用），只把真实状态摆出来
            statusText = if (accessibilityStuck) "需重启设备" else null,
            onClick = onAccessibility
        ),
        PermEntry(
            title = "通知权限",
            desc = "扫描与执行结果通过通知回传",
            ok = notifications,
            onClick = onNotification
        ),
        PermEntry(
            title = "悬浮窗",
            desc = "在游戏画面上显示控制按钮",
            ok = overlay,
            onClick = onOverlay
        ),
        PermEntry(
            title = "后台弹出界面",
            desc = "允许从后台把助手界面顶到前台",
            ok = bgPopup,
            optional = true,
            onClick = onBgPopup
        ),
        PermEntry(
            title = "定时提醒（精确闹钟）",
            desc = when {
                !alarm -> "到点准点提醒你解锁手机"
                testing -> "闹钟将在约 $alarmRemain 秒后响铃"
                else -> "创建一个约 1 分钟后的真实闹钟"
            },
            ok = alarm,
            optional = true,
            onClick = when {
                !alarm -> onAlarm
                testing -> onAlarmOpen
                else -> onAlarmTest
            },
            actionText = when {
                !alarm -> null
                testing -> "打开时钟"
                else -> "试一下"
            },
            onAction = when {
                !alarm -> null
                testing -> onAlarmOpen
                else -> onAlarmTest
            }
        ),
        PermEntry(
            title = "自启动",
            desc = "允许系统在后台拉起助手",
            ok = autoStart,
            optional = true,
            onClick = onAutoStart
        ),
        PermEntry(
            title = "省电策略",
            desc = "设为「无限制」，避免后台被清理",
            ok = battery,
            optional = true,
            onClick = onBattery
        ),
        PermEntry(
            title = "无障碍保活开关",
            desc = "在快捷面板加一个磁贴，降低服务被回收的概率",
            ok = tile,
            optional = true,
            onClick = onTile
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = pad, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "权限准备",
                color = TextPrimary,
                fontSize = if (wide) 28.sp else 24.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Surface2)
                    .clickable { onRefresh() }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Text("刷新", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }

        val listModifier = Modifier
            .weight(1f)
            .widthIn(max = ContentMaxWidth)
            .fillMaxWidth()
        if (wide) {
            // 平板：两列网格，8 项一屏放下，不用滚动
            LazyVerticalGrid(
                columns = GridCells.Adaptive(360.dp),
                modifier = listModifier,
                contentPadding = PaddingValues(start = pad, end = pad, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                gridItems(entries, key = { it.title }) { e ->
                    PermCard(
                        title = e.title,
                        desc = e.desc,
                        ok = e.ok,
                        onClick = e.onClick,
                        optional = e.optional,
                        statusText = e.statusText,
                        actionText = e.actionText,
                        onAction = e.onAction
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = listModifier,
                contentPadding = PaddingValues(start = pad, end = pad, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(entries, key = { it.title }) { e ->
                    PermCard(
                        title = e.title,
                        desc = e.desc,
                        ok = e.ok,
                        onClick = e.onClick,
                        optional = e.optional,
                        statusText = e.statusText,
                        actionText = e.actionText,
                        onAction = e.onAction
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface1)
        ) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .widthIn(max = ActionMaxWidth)
                        .fillMaxWidth()
                        .padding(horizontal = pad, vertical = 12.dp)
                        .height(50.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (ready) Cyan else Surface2)
                        .clickable(enabled = ready) { onEnter() },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "进入账号管理",
                            color = if (ready) Color(0xFF04262B) else TextTertiary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (!ready) {
                            Spacer(Modifier.width(8.dp))
                            Text("还差 $missing 项", color = TextTertiary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/** 权限卡片的数据描述，手机单列 / 平板网格共用同一份内容 */
private data class PermEntry(
    val title: String,
    val desc: String,
    val ok: Boolean,
    val onClick: () -> Unit,
    val optional: Boolean = false,
    /** 右侧状态文字覆盖（默认「已开启 / 去开启」），用于「已授权但服务被系统关掉」这类异常态 */
    val statusText: String? = null,
    val actionText: String? = null,
    val onAction: (() -> Unit)? = null,
)

@Composable
private fun PermCard(
    title: String,
    desc: String,
    ok: Boolean,
    onClick: () -> Unit,
    /** 可选增强项：未开启时用中性色，不摆出「出错」的红色告警感 */
    optional: Boolean = false,
    /** 右侧状态文字覆盖，非空时优先显示（异常态用，如「需重启设备」） */
    statusText: String? = null,
    /** 右侧按钮文字；null 表示右侧只显示「已开启 / 去开启」的状态文字 */
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val accent = when {
        ok && statusText == null -> Cyan
        optional -> TextTertiary
        else -> Warn
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1)
            .border(
                1.dp,
                if (ok || optional) Line else Warn.copy(alpha = 0.28f),
                RoundedCornerShape(16.dp)
            )
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(desc, color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(10.dp))
        if (actionText != null && onAction != null) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (ok) Cyan.copy(alpha = 0.16f) else Surface2)
                    .clickable { onAction() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(actionText, color = if (ok) Cyan else TextSecondary, fontSize = 11.sp)
            }
        } else {
            // 右侧只表示状态，不可点；要操作就点整张卡片
            Text(
                statusText ?: if (ok) "已开启" else "去开启",
                color = when {
                    statusText != null -> Warn
                    ok -> Cyan
                    optional -> TextTertiary
                    else -> Warn
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
