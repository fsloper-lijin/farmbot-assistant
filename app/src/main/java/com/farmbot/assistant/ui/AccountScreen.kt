package com.farmbot.assistant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.farmbot.assistant.data.Account
import com.farmbot.assistant.data.Farm
import com.farmbot.assistant.data.LoginType
import com.farmbot.assistant.ui.theme.Bg
import com.farmbot.assistant.ui.theme.Cyan
import com.farmbot.assistant.ui.theme.Line
import com.farmbot.assistant.ui.theme.Purple
import com.farmbot.assistant.ui.theme.Surface1
import com.farmbot.assistant.ui.theme.Surface2
import com.farmbot.assistant.ui.theme.TextPrimary
import com.farmbot.assistant.ui.theme.TextSecondary
import com.farmbot.assistant.ui.theme.TextTertiary
import com.farmbot.assistant.ui.theme.Warn

@Composable
fun AccountScreen(
    accounts: List<Account>,
    accessibilityOn: Boolean,
    scanResult: List<Farm>?,
    scanTarget: String,
    onAdd: () -> Unit,
    onChange: (Account) -> Unit,
    onRemove: (String) -> Unit,
    onOpenAccessibility: () -> Unit,
    onScan: (Account) -> Unit,
    onApplyScan: () -> Unit,
    onDismissScan: () -> Unit,
    onStart: () -> Unit,
) {
    val farmCount = accounts.sumOf { it.farms.size }
    val selected = accounts.sumOf { it.activeFarms.size }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        Header(accounts.size, farmCount, selected)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { StatusCard(accessibilityOn, onOpenAccessibility) }

            if (scanResult != null) {
                item { ScanBanner(scanResult.size, scanTarget, onApplyScan, onDismissScan) }
            }

            if (accounts.isEmpty()) {
                item { EmptyHint() }
            }

            itemsIndexed(accounts, key = { _, a -> a.id }) { index, acc ->
                AccountCard(
                    index = index,
                    acc = acc,
                    onChange = onChange,
                    onRemove = { onRemove(acc.id) },
                    onScan = { onScan(acc) }
                )
            }

            item { AddAccountButton(onAdd) }
            item { FooterNote() }
        }

        BottomBar(
            count = selected,
            enabled = selected > 0,
            onStart = onStart
        )
    }
}

@Composable
private fun BottomBar(count: Int, enabled: Boolean, onStart: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface1)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Line)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp)
                .height(50.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (enabled) Cyan else Surface2)
                .clickable(enabled = enabled) { onStart() },
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "开始执行",
                    color = if (enabled) Color(0xFF04262B) else TextTertiary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                if (count > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF04262B).copy(alpha = if (enabled) 0.16f else 0.35f))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "$count 个农场",
                            color = if (enabled) Color(0xFF04262B) else TextTertiary,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(accounts: Int, farms: Int, selected: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("农场助手", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text("多账号 · 王者农场自动化", color = TextSecondary, fontSize = 12.sp)
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Cyan.copy(alpha = 0.12f))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                if (farms == 0) "$accounts 个账号" else "$accounts 账号 · $selected/$farms 农场",
                color = Cyan,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun StatusCard(on: Boolean, onClick: () -> Unit) {
    val c = if (on) Cyan else Warn
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.copy(alpha = 0.08f))
            .border(1.dp, c.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(c)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (on) "无障碍服务已开启" else "无障碍服务未开启",
                color = TextPrimary,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (on) "点击前往系统设置查看" else "点击前往设置开启，否则无法模拟点击",
                color = TextSecondary,
                fontSize = 12.sp
            )
        }
        Text("›", color = c, fontSize = 18.sp)
    }
}

@Composable
private fun AccountCard(
    index: Int,
    acc: Account,
    onChange: (Account) -> Unit,
    onRemove: () -> Unit,
    onScan: () -> Unit,
) {
    var expanded by remember(acc.id) { mutableStateOf(true) }
    val accent = if (acc.loginType == LoginType.QQ) Cyan else Purple
    val shape = RoundedCornerShape(18.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Surface1)
            .border(1.dp, Line, shape)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Text("${index + 1}", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (acc.name.isEmpty()) {
                    Text(
                        "账号备注，如：主号",
                        color = TextTertiary,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
                BasicTextField(
                    value = acc.name,
                    onValueChange = { onChange(acc.copy(name = it)) },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    cursorBrush = SolidColor(accent),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable { onRemove() },
                contentAlignment = Alignment.Center
            ) {
                Text("✕", color = TextTertiary, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            LoginTypePicker(acc.loginType, accent) { onChange(acc.copy(loginType = it)) }
            Spacer(Modifier.width(8.dp))
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 8.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (acc.farms.isEmpty()) "暂无农场"
                    else "${acc.activeFarms.size}/${acc.farms.size} 个农场",
                    color = if (acc.farms.isEmpty()) TextTertiary else TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (expanded) "收起" else "展开",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
                Spacer(Modifier.width(4.dp))
                Caret(up = expanded, color = TextSecondary)
            }
        }

        if (expanded) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Line)
            )
            Spacer(Modifier.height(6.dp))

            acc.farms.forEach { farm ->
                FarmRow(
                    farm = farm,
                    accent = accent,
                    onChange = { changed ->
                        onChange(acc.copy(farms = acc.farms.map { if (it.id == changed.id) changed else it }))
                    },
                    onRemove = {
                        onChange(acc.copy(farms = acc.farms.filterNot { it.id == farm.id }))
                    }
                )
            }

            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiniButton("＋ 添加农场", accent) {
                    onChange(acc.copy(farms = acc.farms + Farm()))
                }
                Spacer(Modifier.width(6.dp))
                MiniButton("扫描区服", Cyan, onScan)
            }
        }
    }
}

@Composable
private fun FarmRow(
    farm: Farm,
    accent: Color,
    onChange: (Farm) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top
    ) {
        CheckDot(farm.enabled, accent) { onChange(farm.copy(enabled = !farm.enabled)) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Box {
                if (farm.server.isEmpty()) {
                    Text("区服，如 手Q549区 蔬乡物语", color = TextTertiary, fontSize = 13.sp)
                }
                BasicTextField(
                    value = farm.server,
                    onValueChange = { onChange(farm.copy(server = it)) },
                    singleLine = true,
                    textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp),
                    cursorBrush = SolidColor(accent),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (farm.role.isEmpty()) {
                        Text("角色名", color = TextTertiary, fontSize = 11.sp)
                    }
                    BasicTextField(
                        value = farm.role,
                        onValueChange = { onChange(farm.copy(role = it)) },
                        singleLine = true,
                        textStyle = TextStyle(color = TextSecondary, fontSize = 11.sp),
                        cursorBrush = SolidColor(accent),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (farm.level > 0) {
                    Text("Lv.${farm.level}", color = TextTertiary, fontSize = 11.sp)
                }
            }
        }
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .clickable { onRemove() },
            contentAlignment = Alignment.Center
        ) {
            Text("✕", color = TextTertiary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun CheckDot(checked: Boolean, accent: Color, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (checked) accent.copy(alpha = 0.9f) else Color.Transparent)
            .border(
                1.5.dp,
                if (checked) accent else Line,
                RoundedCornerShape(6.dp)
            )
            .clickable { onToggle() },
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Canvas(Modifier.size(11.dp)) {
                val p = Path().apply {
                    moveTo(0f, size.height * 0.52f)
                    lineTo(size.width * 0.38f, size.height * 0.82f)
                    lineTo(size.width, size.height * 0.18f)
                }
                drawPath(p, Bg, style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = size.width * 0.24f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round
                ))
            }
        }
    }
}

@Composable
private fun Caret(up: Boolean, color: Color) {
    Canvas(Modifier.size(8.dp).rotate(if (up) 180f else 0f)) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(0f, h * 0.32f)
            lineTo(w, h * 0.32f)
            lineTo(w / 2f, h * 0.78f)
            close()
        }
        drawPath(p, color)
    }
}

@Composable
private fun LoginTypePicker(value: LoginType, accent: Color, onChange: (LoginType) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Surface2)
                .clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(value.label, color = accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            Caret(up = false, color = TextSecondary)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(12.dp),
            containerColor = Surface2,
            border = BorderStroke(1.dp, Line),
        ) {
            LoginType.entries.forEach { t ->
                DropdownMenuItem(
                    text = {
                        Text(
                            t.label,
                            color = if (t == value) Cyan else TextPrimary,
                            fontSize = 14.sp
                        )
                    },
                    onClick = {
                        onChange(t)
                        open = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ScanBanner(count: Int, target: String, onApply: () -> Unit, onDismiss: () -> Unit) {
    val c = if (count > 0) Cyan else Warn
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.copy(alpha = 0.08f))
            .border(1.dp, c.copy(alpha = 0.30f), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Text(
            if (count > 0) "扫描到 $count 个区服" else "未识别到区服",
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (count > 0) "确认加入该账号？已存在的区服会自动跳过"
            else "请先在游戏内打开「选择服务器 → 我的服务器」面板后重试",
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        if (count > 0) {
            Spacer(Modifier.height(11.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(c.copy(alpha = 0.18f))
                        .clickable { onApply() }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (target.isEmpty()) "全部加入" else "全部加入「$target」",
                        color = c,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .border(1.dp, Line, RoundedCornerShape(9.dp))
                        .clickable { onDismiss() }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("忽略", color = TextSecondary, fontSize = 13.sp)
                }
            }
        } else {
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .border(1.dp, Line, RoundedCornerShape(9.dp))
                    .clickable { onDismiss() }
                    .padding(horizontal = 18.dp, vertical = 9.dp)
            ) {
                Text("知道了", color = TextSecondary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun MiniButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(label, color = color, fontSize = 12.sp)
    }
}

@Composable
private fun AddAccountButton(onAdd: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface2.copy(alpha = 0.55f))
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .clickable { onAdd() }
            .padding(vertical = 15.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("＋", color = Cyan, fontSize = 15.sp)
        Spacer(Modifier.width(8.dp))
        Text("添加账号", color = Cyan, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun EmptyHint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, Line, RoundedCornerShape(16.dp))
            .padding(horizontal = 18.dp, vertical = 30.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "还没有账号\n点击下方「添加账号」，每个账号对应一次独立的王者荣耀登录",
            color = TextTertiary,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun FooterNote() {
    Text(
        "一个账号可下辖多个区服农场。执行时会按顺序：登录账号 → 换区 → 进入农场 → 执行任务。",
        color = TextTertiary,
        fontSize = 11.sp,
        lineHeight = 17.sp,
        modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp)
    )
}
