package com.farmbot.assistant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed as staggeredItemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.farmbot.assistant.data.Account
import com.farmbot.assistant.data.Farm
import com.farmbot.assistant.data.LoginType
import com.farmbot.assistant.ui.theme.Bg
import com.farmbot.assistant.ui.theme.Cyan
import com.farmbot.assistant.ui.theme.Danger
import com.farmbot.assistant.ui.theme.Line
import com.farmbot.assistant.ui.theme.Purple
import com.farmbot.assistant.ui.theme.Surface1
import com.farmbot.assistant.ui.theme.Surface2
import com.farmbot.assistant.ui.theme.TextPrimary
import com.farmbot.assistant.ui.theme.TextSecondary
import com.farmbot.assistant.ui.theme.TextTertiary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun AccountScreen(
    accounts: List<Account>,
    onAdd: () -> Unit,
    onChange: (Account) -> Unit,
    onRemove: (String) -> Unit,
    /** 调整执行顺序：id, -1 上移（提前执行）/ +1 下移 */
    onMove: (String, Int) -> Unit,
    onStart: () -> Unit,
    /** 同步区服：把所有已保存的账号逐个登录一遍，刷新各自的区服列表（不跑农场任务） */
    onSync: () -> Unit,
    /** 自动化是否在跑；决定「停止」按钮是否可点 */
    running: Boolean,
    onStop: () -> Unit,
    /** 导入账号：从 JSON 备份文件合并账号与区服 */
    onImport: () -> Unit,
    /** 导出账号：把当前账号、区服及勾选状态写成 JSON 文件 */
    onExport: () -> Unit,
    /** 刚点「添加账号」新建的那张卡的 id：只让它播放入场动画 */
    justAddedId: String? = null,
) {
    // 规则页全关（或一键务农三个子功能全关）= 没有任务要执行；农场一个区都没勾 = 没地方执行
    val prefs = LocalContext.current.getSharedPreferences("farmbot", android.content.Context.MODE_PRIVATE)
    val anyRuleOn = anyRuleActive(prefs)
    val anyFarmOn = accounts.any { it.enabled && it.activeFarms.isNotEmpty() }
    val hint = when {
        accounts.isEmpty() -> null
        !anyFarmOn -> "请先勾选区服"
        // 一键务农开着但子功能全关：别说「没启用任务」，用户会以为开关坏了
        !anyRuleOn && prefs.getBoolean("rule_harvest", false) -> "一键务农里还没启用子功能"
        !anyRuleOn -> "请先在规则页启用任务"
        else -> null
    }
    val wide = isWideScreen()
    val pad = if (wide) 28.dp else 18.dp
    val gridState = rememberLazyStaggeredGridState()
    val listState = rememberLazyListState()
    // 「添加账号」把空卡插在最前面：自动回到顶部，新卡一眼可见
    val first = accounts.firstOrNull()
    LaunchedEffect(first?.id) {
        if (first != null && first.name.isBlank() && first.qqNick.isBlank()) {
            if (wide) gridState.animateScrollToItem(0) else listState.animateScrollToItem(0)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Header(onImport, onExport, pad)
        val listModifier = Modifier.fillMaxSize()
        // 列表铺满，操作条以半透明浮层压在列表底部：既能一直看见，又不会把页面切成两截
        Box(Modifier.weight(1f).widthIn(max = ContentMaxWidth).fillMaxWidth()) {
        if (wide) {
            // 平板：交错网格（瀑布流）。卡片展开/收起后高度差很大，等高网格会被最高的一张撑满整行，
            // 矮卡片下面就空出一大块；交错网格总是往最短的那一列塞，后面的卡片会自然把空当挤掉
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Adaptive(560.dp),
                modifier = listModifier,
                state = gridState,
                contentPadding = PaddingValues(start = pad, end = pad, bottom = 100.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalItemSpacing = 14.dp
            ) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    AddAccountRow(onAdd, onSync, syncEnabled = accounts.isNotEmpty() && !running)
                }

                if (accounts.isEmpty()) {
                    item(span = StaggeredGridItemSpan.FullLine) { EmptyHint() }
                }

                staggeredItemsIndexed(accounts, key = { _, a -> a.id }) { index, acc ->
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
                        justAdded = acc.id == justAddedId,
                        modifier = Modifier.animateItem()
                    )
                }

                item(span = StaggeredGridItemSpan.FullLine) { FooterNote() }
            }
        } else {
            LazyColumn(
                modifier = listModifier,
                state = listState,
                contentPadding = PaddingValues(start = pad, end = pad, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { AddAccountRow(onAdd, onSync, syncEnabled = accounts.isNotEmpty() && !running) }

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
                        justAdded = acc.id == justAddedId,
                        modifier = Modifier.animateItem()
                    )
                }

                item { FooterNote() }
            }
        }

        BottomBar(
            // 有账号、有勾选的区服、规则页至少启用一项，才真的有事可做
            enabled = accounts.isNotEmpty() && anyFarmOn && anyRuleOn,
            hint = hint,
            running = running,
            onStart = onStart,
            onStop = onStop,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = pad, vertical = 10.dp)
                .widthIn(max = ActionMaxWidth)
        )
        }
    }
}

@Composable
private fun BottomBar(
    enabled: Boolean,
    /** 不能开始执行的原因，直接替代按钮文字，避免点了才弹提示 */
    hint: String?,
    running: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Surface1.copy(alpha = 0.86f))
            .border(1.dp, Line.copy(alpha = 0.7f), RoundedCornerShape(24.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(46.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (enabled) Cyan else Surface2.copy(alpha = 0.7f))
                .clickable(enabled = enabled) { onStart() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (!enabled && hint != null) hint else "开始执行",
                color = if (enabled) Color(0xFF04262B) else TextTertiary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.width(8.dp))

        // 停止做成描边幽灵按钮，比实心块轻，不抢「开始执行」的视线
        Box(
            modifier = Modifier
                .width(66.dp)
                .height(38.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, Line, RoundedCornerShape(16.dp))
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

@Composable
private fun Header(onImport: () -> Unit, onExport: () -> Unit, pad: Dp) {
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
        HeaderAction("导入", onImport)
        Spacer(Modifier.width(8.dp))
        HeaderAction("导出", onExport)
    }
}

/** 右上角的小胶囊按钮：导入 / 导出账号 */
@Composable
private fun HeaderAction(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Surface2)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(label, color = TextSecondary, fontSize = 12.sp)
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
    justAdded: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(acc.id) { mutableStateOf(true) }
    // 「全选/取消全选」的批次号：每点一次自增，各行据此按顺序错开动画
    var batch by remember(acc.id) { mutableStateOf(0) }
    // 当前滑出垃圾桶的那一行；非空时其它行保持收起
    var swipedFarm by remember(acc.id) { mutableStateOf<String?>(null) }
    // 删账号的二次确认弹窗
    var confirmDelete by remember(acc.id) { mutableStateOf(false) }
    val isQQ = acc.loginType == LoginType.QQ || acc.loginType == LoginType.QQ_QR
    val accent = if (isQQ) Cyan else Purple
    val shape = RoundedCornerShape(18.dp)
    // 刚点击「添加账号」插进来的空卡：从略小、透明「弹」到正常大小
    val appear = remember(acc.id) { Animatable(if (justAdded) 0f else 1f) }
    LaunchedEffect(justAdded) {
        if (justAdded) {
            appear.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow))
        }
    }

    Column(
        modifier = modifier
            .graphicsLayer {
                val p = appear.value
                alpha = p
                scaleX = 0.94f + 0.06f * p
                scaleY = 0.94f + 0.06f * p
                transformOrigin = TransformOrigin(0.5f, 0f)
            }
            .fillMaxWidth()
            .clip(shape)
            .background(Surface1)
            .border(1.dp, Line, shape)
            .padding(horizontal = 20.dp, vertical = 18.dp)
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
                    .clickable { confirmDelete = true },
                contentAlignment = Alignment.Center
            ) {
                Text("✕", color = TextTertiary, fontSize = 13.sp)
            }
        }

        // 删除账号要二次确认：第一下只弹提示，避免点偏就把整张卡连同区服删掉
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = {
                    Text("删除这个账号？", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                },
                text = {
                    Text(
                        "「${acc.name.ifBlank { "未命名账号" }}」以及它下面的 ${acc.farms.size} 个区服会一起删除，删除后不能恢复。",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                },
                confirmButton = {
                    Text(
                        "删除",
                        color = Danger,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                confirmDelete = false
                                onRemove()
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                },
                dismissButton = {
                    Text(
                        "取消",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { confirmDelete = false }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                },
                containerColor = Surface1,
                textContentColor = TextSecondary,
                titleContentColor = TextPrimary,
                shape = RoundedCornerShape(16.dp)
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            // 左边是这个账号本身的名字：QQ 账号昵称（微信账号就是微信昵称）
            val nick = if (isQQ) acc.qqNick else acc.name
            Column(Modifier.weight(1f)) {
            Box {
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
            // 昵称下面那行放 QQ 号：切号时 OCR 到的会自动填上，没识别到也能手动输入
            if (isQQ) {
                Spacer(Modifier.height(3.dp))
                Box {
                    if (acc.qqNo.isEmpty()) {
                        Text(
                            "QQ 号，如：123456789",
                            color = TextTertiary.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                    BasicTextField(
                        value = acc.qqNo,
                        onValueChange = { onChange(acc.copy(qqNo = it.filter { c -> c.isDigit() }.take(12))) },
                        singleLine = true,
                        textStyle = TextStyle(color = TextSecondary, fontSize = 12.sp),
                        cursorBrush = SolidColor(accent),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            }
            Spacer(Modifier.width(10.dp))
            // 右列：身份下拉 + 农场数量都贴右，跟左列的「昵称 / QQ 号」组成对称两栏，
            // 不然小字散在整行两侧，视觉重心会往右下角坠
            Column(horizontalAlignment = Alignment.End) {
                if (isQQ) {
                    IdentityPicker(acc.name, acc.identities, accent) { onChange(acc.copy(name = it)) }
                    Spacer(Modifier.height(2.dp))
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
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
        }

        if (expanded) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Line)
            )
            Spacer(Modifier.height(4.dp))

            // 区服列表的头部：左边全选/全不选，右边手动补一个区服
            val allOn = acc.farms.isNotEmpty() && acc.farms.all { it.enabled }
            val toggleAll = {
                if (acc.farms.isNotEmpty()) {
                    val next = !allOn
                    // 递增批次号：让每行知道这次是「全选」触发的，按行号依次延迟出现/消失
                    batch++
                    onChange(acc.copy(farms = acc.farms.map { it.copy(enabled = next) }))
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 全选这一行和下面的区服行之间留出距离，避免两个复选框挤在一起看错
                    .padding(top = 4.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = acc.farms.isNotEmpty()) { toggleAll() }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CheckDot(allOn, accent, toggleAll)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (allOn) "取消全选" else "全选",
                        color = if (acc.farms.isEmpty()) TextTertiary else TextSecondary,
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                        .clickable { onChange(acc.copy(farms = acc.farms + Farm())) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("＋ 添加服", color = accent, fontSize = 11.sp)
                }
            }

            if (acc.farms.isEmpty()) {
                Text(
                    "还没有区服，点右上「添加服」手动填，或用上面的「同步区服」自动抓",
                    color = TextTertiary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }

            acc.farms.forEachIndexed { index, farm ->
                FarmRow(
                    farm = farm,
                    accent = accent,
                    // 第 index 行：全选时延后 index*35ms，形成从上往下依次出现的涟漪
                    batch = batch,
                    stagger = index * 35,
                    // 同时只允许一行处于「已滑出垃圾桶」状态
                    swiped = swipedFarm == farm.id,
                    onSwipedChange = { open ->
                        swipedFarm = when {
                            open -> farm.id
                            swipedFarm == farm.id -> null
                            else -> swipedFarm
                        }
                    },
                    onChange = { changed ->
                        onChange(acc.copy(farms = acc.farms.map { if (it.id == changed.id) changed else it }))
                    },
                    onRemove = {
                        onChange(acc.copy(farms = acc.farms.filterNot { it.id == farm.id }))
                    }
                )
            }
        }
    }
}

@Composable
private fun FarmRow(
    farm: Farm,
    accent: Color,
    batch: Int = 0,
    stagger: Int = 0,
    /** 这一行是否处于滑出垃圾桶状态（由卡片统一管理，保证同时只有一行） */
    swiped: Boolean = false,
    onSwipedChange: (Boolean) -> Unit = {},
    onChange: (Farm) -> Unit,
    onRemove: () -> Unit,
) {
    SwipeToDelete(
        open = swiped,
        onOpenChange = onSwipedChange,
        onDelete = onRemove
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Bg)
                .padding(vertical = 7.dp),
            verticalAlignment = Alignment.Top
        ) {
            CheckDot(farm.enabled, accent, { onChange(farm.copy(enabled = !farm.enabled)) }, batch, stagger)
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
        }
    }
}

/**
 * 微信聊天式左滑删除：向左拖出红色垃圾桶，再点垃圾桶才真的删。
 * 拖不到一半松手会弹回去，避免误删。
 */
@Composable
private fun SwipeToDelete(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val revealPx = with(density) { 68.dp.toPx() }
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // 展开状态由卡片统一持有：别的行滑开时，这一行自动退回
    LaunchedEffect(open) {
        offsetX.animateTo(if (open) -revealPx else 0f, tween(180))
    }
    Box(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.matchParentSize(),
            horizontalArrangement = Arrangement.End
        ) {
            Box(
                modifier = Modifier
                    .width(60.dp)
                    .fillMaxHeight()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Danger.copy(alpha = 0.12f))
                    .border(1.dp, Danger.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                    .clickable { onDelete() },
                contentAlignment = Alignment.Center
            ) {
                TrashIcon()
            }
        }
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            offsetX.snapTo((offsetX.value + delta).coerceIn(-revealPx, 0f))
                        }
                    },
                    onDragStopped = {
                        val nowOpen = offsetX.value < -revealPx * 0.45f
                        scope.launch {
                            offsetX.animateTo(if (nowOpen) -revealPx else 0f, tween(180))
                        }
                        onOpenChange(nowOpen)
                    }
                )
        ) {
            content()
        }
    }
}

@Composable
private fun TrashIcon() {
    Canvas(Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.11f
        val c = Danger
        // 桶盖
        drawLine(c, Offset(w * 0.12f, h * 0.24f), Offset(w * 0.88f, h * 0.24f), stroke, StrokeCap.Round)
        // 提手
        drawLine(c, Offset(w * 0.36f, h * 0.24f), Offset(w * 0.38f, h * 0.10f), stroke, StrokeCap.Round)
        drawLine(c, Offset(w * 0.62f, h * 0.24f), Offset(w * 0.60f, h * 0.10f), stroke, StrokeCap.Round)
        drawLine(c, Offset(w * 0.38f, h * 0.10f), Offset(w * 0.62f, h * 0.10f), stroke, StrokeCap.Round)
        // 桶身
        val p = Path().apply {
            moveTo(w * 0.22f, h * 0.24f)
            lineTo(w * 0.28f, h * 0.9f)
            lineTo(w * 0.72f, h * 0.9f)
            lineTo(w * 0.78f, h * 0.24f)
        }
        drawPath(p, c, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // 两道竖纹
        drawLine(c, Offset(w * 0.42f, h * 0.4f), Offset(w * 0.44f, h * 0.74f), stroke * 0.8f, StrokeCap.Round)
        drawLine(c, Offset(w * 0.58f, h * 0.4f), Offset(w * 0.56f, h * 0.74f), stroke * 0.8f, StrokeCap.Round)
    }
}

@Composable
private fun CheckDot(
    checked: Boolean,
    accent: Color,
    onToggle: () -> Unit,
    /** 「全选」批次号：变化说明这次是批量操作，需要按 stagger 错开 */
    batch: Int = 0,
    /** 错开时长（毫秒），由上往下的行号决定 */
    stagger: Int = 0,
) {
    // 底色和描边跟着勾选状态淡入淡出，不再硬切
    val fill by animateColorAsState(
        if (checked) accent.copy(alpha = 0.9f) else Color.Transparent,
        tween(160),
        label = "dotFill"
    )
    val stroke by animateColorAsState(
        if (checked) accent else Line,
        tween(160),
        label = "dotStroke"
    )
    // 勾号：勾上时带一点回弹放大出来，取消时快速缩回淡出
    val mark = remember { Animatable(if (checked) 1f else 0f) }
    // 只有「全选」批次变化时才延迟，单点某一个不受影响
    var lastBatch by remember { mutableStateOf(batch) }
    LaunchedEffect(checked, batch) {
        val fromBatch = batch != lastBatch
        lastBatch = batch
        if (fromBatch && stagger > 0) delay(stagger.toLong())
        if (checked) {
            mark.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMedium))
        } else {
            mark.animateTo(0f, tween(110))
        }
    }
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(fill)
            .border(1.5.dp, stroke, RoundedCornerShape(6.dp))
            .clickable { onToggle() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            Modifier
                .size(11.dp)
                .graphicsLayer {
                    val m = mark.value
                    scaleX = m
                    scaleY = m
                    alpha = m
                }
        ) {
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
private fun AddAccountRow(onAdd: () -> Unit, onSync: () -> Unit, syncEnabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(50.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Surface2.copy(alpha = 0.55f))
                .border(1.dp, Line, RoundedCornerShape(14.dp))
                .clickable { onAdd() },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("＋", color = Cyan, fontSize = 15.sp)
            Spacer(Modifier.width(8.dp))
            Text("添加账号", color = Cyan, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Row(
            modifier = Modifier
                .width(150.dp)
                .height(50.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (syncEnabled) Surface2 else Surface2.copy(alpha = 0.3f))
                .border(1.dp, Line, RoundedCornerShape(14.dp))
                .clickable(enabled = syncEnabled) { onSync() },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "同步区服",
                color = if (syncEnabled) TextPrimary else TextTertiary.copy(alpha = 0.5f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
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
        "一个账号可下辖多个区服农场。执行时会按顺序：登录账号 → 进入农场 → 执行任务；区服由「同步区服」单独维护。",
        color = TextTertiary,
        fontSize = 11.sp,
        lineHeight = 17.sp,
        modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp)
    )
}
