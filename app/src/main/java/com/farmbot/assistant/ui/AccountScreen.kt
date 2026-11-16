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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
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
import androidx.compose.ui.unit.Dp
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
    scanResult: List<Farm>?,
    scanTarget: String,
    onAdd: () -> Unit,
    onChange: (Account) -> Unit,
    onRemove: (String) -> Unit,
    /** 调整执行顺序：id, -1 上移（提前执行）/ +1 下移 */
    onMove: (String, Int) -> Unit,
    onScan: (Account) -> Unit,
    onTestLogin: (Account) -> Unit,
    onApplyScan: () -> Unit,
    onDismissScan: () -> Unit,
    onStart: () -> Unit,
    /** 自动化是否在跑；决定「停止」按钮是否可点 */
    running: Boolean,
    onStop: () -> Unit,
    onOpenPerms: () -> Unit,
) {
    val farmCount = accounts.sumOf { it.farms.size }
    val selected = accounts.sumOf { it.activeFarms.size }
    val wide = isWideScreen()
    val pad = if (wide) 28.dp else 18.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Header(accounts.size, farmCount, selected, onOpenPerms, pad)
        val listModifier = Modifier
            .weight(1f)
            .widthIn(max = ContentMaxWidth)
            .fillMaxWidth()
        if (wide) {
            // 平板：账号卡片按列铺开，单张卡片不至于横向拉满整屏
            LazyVerticalGrid(
                columns = GridCells.Adaptive(560.dp),
                modifier = listModifier,
                contentPadding = PaddingValues(start = pad, end = pad, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    AddAccountButton(onAdd)
                }

                if (scanResult != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        ScanBanner(scanResult.size, scanTarget, onApplyScan, onDismissScan)
                    }
                }

                if (accounts.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { EmptyHint() }
                }

                gridItemsIndexed(accounts, key = { _, a -> a.id }) { index, acc ->
                    AccountCard(
                        index = index,
                        acc = acc,
                        canUp = index > 0,
                        canDown = index < accounts.lastIndex,
                        // 微信同一时间只能登录一个账号：已有别的微信账号时禁止再选微信
                        wxTaken = accounts.any { it.id != acc.id && (it.loginType == LoginType.WECHAT || it.loginType == LoginType.WECHAT_QR) },
                        onChange = onChange,
                        onRemove = { onRemove(acc.id) },
                        onMoveUp = { onMove(acc.id, -1) },
                        onMoveDown = { onMove(acc.id, 1) },
                        onScan = { onScan(acc) },
                        onTestLogin = { onTestLogin(acc) }
                    )
                }

                item(span = { GridItemSpan(maxLineSpan) }) { FooterNote() }
            }
        } else {
            LazyColumn(
                modifier = listModifier,
                contentPadding = PaddingValues(start = pad, end = pad, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { AddAccountButton(onAdd) }

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
                        canUp = index > 0,
                        canDown = index < accounts.lastIndex,
                        // 微信同一时间只能登录一个账号：已有别的微信账号时禁止再选微信
                        wxTaken = accounts.any { it.id != acc.id && (it.loginType == LoginType.WECHAT || it.loginType == LoginType.WECHAT_QR) },
                        onChange = onChange,
                        onRemove = { onRemove(acc.id) },
                        onMoveUp = { onMove(acc.id, -1) },
                        onMoveDown = { onMove(acc.id, 1) },
                        onScan = { onScan(acc) },
                        onTestLogin = { onTestLogin(acc) }
                    )
                }

                item { FooterNote() }
            }
        }

        BottomBar(
            count = selected,
            // 三种情况可点：
            //   有选中农场 → 正常执行任务；
            //   账号非空但没选农场 → 自动登录第一个启用账号（不需要先选农场）；
            //   一个账号都没有 → 走「登录 → 自动识别账号 → 同步区服」的引导
            enabled = selected > 0 || accounts.isNotEmpty(),
            running = running,
            onStart = onStart,
            onStop = onStop,
            pad = pad
        )
    }
}

@Composable
private fun BottomBar(
    count: Int,
    enabled: Boolean,
    running: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    pad: Dp,
) {
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
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier
                    .widthIn(max = ActionMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = pad, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
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

                Spacer(Modifier.width(10.dp))

                // 强制停止：只在自动化运行中可点，空闲时保持灰色
                Box(
                    modifier = Modifier
                        .width(68.dp)
                        .height(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Surface2)
                        .border(1.dp, Line, RoundedCornerShape(12.dp))
                        .clickable(enabled = running) { onStop() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "停止",
                        color = if (running) TextPrimary else TextTertiary.copy(alpha = 0.45f),
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(accounts: Int, farms: Int, selected: Int, onOpenPerms: () -> Unit, pad: Dp) {
    Row(
        modifier = Modifier
            .widthIn(max = ContentMaxWidth)
            .fillMaxWidth()
            .padding(horizontal = pad, vertical = 18.dp),
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
                .background(Surface2)
                .clickable { onOpenPerms() }
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text("权限", color = TextSecondary, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
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

/** 调整执行顺序的小箭头：上移 = 提前执行，下移 = 与后一个账号交换；不可用时置灰且不响应点击 */
@Composable
private fun MoveArrow(up: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (up) "↑" else "↓",
            color = if (enabled) TextSecondary else TextTertiary.copy(alpha = 0.35f),
            fontSize = 15.sp
        )
    }
}

@Composable
private fun AccountCard(
    index: Int,
    acc: Account,
    wxTaken: Boolean,
    /** 第一个账号不能上移、最后一个不能下移；只有一个账号时两个都不可点（置灰） */
    canUp: Boolean,
    canDown: Boolean,
    onChange: (Account) -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onScan: () -> Unit,
    onTestLogin: () -> Unit,
) {
    var expanded by remember(acc.id) { mutableStateOf(true) }
    val isQQ = acc.loginType == LoginType.QQ || acc.loginType == LoginType.QQ_QR
    val accent = if (isQQ) Cyan else Purple
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
            // 先选登录方式：执行时会按这里的方式登进去，再自动回填账号名
            LoginTypePicker(acc.loginType, accent, wxTaken) { onChange(acc.copy(loginType = it)) }
            Spacer(Modifier.weight(1f))
            MoveArrow(up = true, enabled = canUp, onClick = onMoveUp)
            MoveArrow(up = false, enabled = canDown, onClick = onMoveDown)
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

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            // 左边是这个账号本身的名字：QQ 账号昵称（微信账号就是微信昵称）
            val nick = if (isQQ) acc.qqNick else acc.name
            Box(Modifier.weight(1f)) {
                if (nick.isEmpty()) {
                    Text(
                        if (isQQ) "QQ 昵称，如：主号" else "微信昵称，如：主号",
                        color = TextTertiary,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
                BasicTextField(
                    value = nick,
                    onValueChange = {
                        onChange(if (isQQ) acc.copy(qqNick = it) else acc.copy(name = it))
                    },
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
            // 右边是这个账号授权给游戏的哪个身份。微信没有多身份，省掉
            if (isQQ) {
                Spacer(Modifier.width(8.dp))
                IdentityPicker(acc.name, acc.identities, accent) { onChange(acc.copy(name = it)) }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
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
                Spacer(Modifier.width(6.dp))
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
                MiniButton("试登录", accent, onTestLogin)
                Spacer(Modifier.width(6.dp))
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
private fun LoginTypePicker(
    value: LoginType,
    accent: Color,
    wxTaken: Boolean,
    onChange: (LoginType) -> Unit,
) {
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
                val wx = t == LoginType.WECHAT || t == LoginType.WECHAT_QR
                val blocked = wx && wxTaken
                DropdownMenuItem(
                    enabled = !blocked,
                    text = {
                        Text(
                            if (blocked) "${t.label}（微信仅支持一个账号）" else t.label,
                            color = when {
                                blocked -> TextTertiary
                                t == value -> Cyan
                                else -> TextPrimary
                            },
                            fontSize = 14.sp
                        )
                    },
                    onClick = {
                        if (blocked) return@DropdownMenuItem
                        onChange(t)
                        open = false
                    }
                )
            }
        }
    }
}

/**
 * 游戏身份选择：选项来自 QQ 授权页那张「你可以使用不同的头像/昵称登录」列表，
 * 第一个是 QQ 原始身份，后面是「创建」出来的分身。微信没有这个，不显示本组件。
 */
@Composable
private fun IdentityPicker(
    value: String,
    options: List<String>,
    accent: Color,
    onChange: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    // 还没抓到身份列表时，把已知的名字当成唯一选项，避免出现点了没反应的空下拉
    val choices = options.ifEmpty { if (value.isEmpty()) emptyList() else listOf(value) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Surface2)
                .clickable(enabled = choices.isNotEmpty()) { open = true }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                value.ifEmpty { "身份未识别" },
                color = if (value.isEmpty()) TextTertiary else accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            if (choices.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Caret(up = false, color = TextSecondary)
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(12.dp),
            containerColor = Surface2,
            border = BorderStroke(1.dp, Line),
        ) {
            choices.forEachIndexed { i, name ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (i == 0) "$name（QQ 原始身份）" else name,
                            color = if (name == value) Cyan else TextPrimary,
                            fontSize = 14.sp
                        )
                    },
                    onClick = {
                        onChange(name)
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
            "还没有账号\n点击上方「添加账号」，每个账号对应一次独立的王者荣耀登录",
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
