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
import androidx.compose.foundation.lazy.LazyColumn
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
    notifications: Boolean,
    overlay: Boolean,
    manual: Boolean,
    onAutoGrant: () -> Unit,
    onAccessibility: () -> Unit,
    onNotification: () -> Unit,
    onOverlay: () -> Unit,
    onManualOpen: () -> Unit,
    onManualToggle: (Boolean) -> Unit,
    onEnter: () -> Unit,
) {
    val required = listOf(accessibility, notifications, overlay)
    val done = required.count { it } + if (manual) 1 else 0
    val missing = required.count { !it }
    val ready = missing == 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("权限准备", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "全部开启后才能进入账号管理",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background((if (ready) Cyan else Warn).copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    "$done/4",
                    color = if (ready) Cyan else Warn,
                    fontSize = 12.sp
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                AutoGrantBlock(
                    accessibility = accessibility,
                    ready = notifications && overlay,
                    onClick = onAutoGrant
                )
            }
            item {
                PermCard(
                    title = "无障碍服务",
                    desc = "用于截屏识别与模拟点击，是自动化的核心权限",
                    ok = accessibility,
                    required = true,
                    onClick = onAccessibility
                )
            }
            item {
                PermCard(
                    title = "通知权限",
                    desc = "扫描与执行进度通过通知回传，缺少时你会看不到结果",
                    ok = notifications,
                    required = true,
                    onClick = onNotification
                )
            }
            item {
                PermCard(
                    title = "悬浮窗",
                    desc = "在游戏画面上显示控制按钮与执行状态",
                    ok = overlay,
                    required = true,
                    onClick = onOverlay
                )
            }
            item {
                PermCard(
                    title = "打开其他应用",
                    desc = "点「开始执行」时系统会弹「启动应用」确认框，此时请选「始终允许」。系统不提供该权限的检测接口，设置完成后请手动勾选右侧",
                    ok = manual,
                    required = false,
                    onClick = onManualOpen,
                    manualToggle = { onManualToggle(!manual) }
                )
            }
            item {
                Text(
                    "提示：部分系统会把「自启动」与「后台弹出界面」放在同一页，建议一并开启，" +
                        "否则切到游戏后助手可能被系统回收。",
                    color = TextTertiary,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp)
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface1)
        ) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp)
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

@Composable
private fun AutoGrantBlock(
    accessibility: Boolean,
    ready: Boolean,
    onClick: () -> Unit,
) {
    val enabled = accessibility && !ready
    val bg = when {
        ready -> Cyan.copy(alpha = 0.14f)
        !accessibility -> Surface2
        else -> Cyan
    }
    val fg = if (enabled) Color(0xFF04262B) else if (ready) Cyan else TextTertiary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .then(if (!enabled) Modifier.border(1.dp, Line, RoundedCornerShape(16.dp)) else Modifier)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 15.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (ready) "通知与悬浮窗已就绪" else "一键引导开启",
                color = if (enabled) fg else if (ready) Cyan else TextSecondary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.weight(1f))
            if (enabled) Text("开始", color = fg, fontSize = 13.sp)
        }
        Spacer(Modifier.height(5.dp))
        Text(
            when {
                ready -> "无需再操作，直接进入账号管理"
                !accessibility -> "需先手动开启无障碍服务"
                else -> "自动依次打开通知与悬浮窗设置页，你各点一下开关即可，返回后自动推进"
            },
            color = if (enabled) Color(0xFF04262B).copy(alpha = 0.72f) else TextTertiary,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    }
}

@Composable
private fun PermCard(
    title: String,
    desc: String,
    ok: Boolean,
    required: Boolean,
    onClick: () -> Unit,
    manualToggle: (() -> Unit)? = null,
) {
    val accent = if (ok) Cyan else Warn
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1)
            .border(1.dp, if (ok) Line else Warn.copy(alpha = 0.28f), RoundedCornerShape(16.dp))
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                if (!required) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(Surface2)
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text("建议", color = TextTertiary, fontSize = 10.sp)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(desc, color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(10.dp))
        if (manualToggle == null) {
            Text(
                if (ok) "已开启" else "去开启",
                color = if (ok) TextTertiary else Cyan,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        } else {
            Box(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (ok) Cyan.copy(alpha = 0.16f) else Surface2)
                    .clickable { manualToggle() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    if (ok) "已完成" else "已设置",
                    color = if (ok) Cyan else TextSecondary,
                    fontSize = 11.sp
                )
            }
        }
    }
}
