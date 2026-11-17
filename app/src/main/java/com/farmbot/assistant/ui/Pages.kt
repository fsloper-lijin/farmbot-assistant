package com.farmbot.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import com.farmbot.assistant.core.IO
import com.farmbot.assistant.update.Updater
import com.farmbot.assistant.ui.theme.Bg
import com.farmbot.assistant.ui.theme.Cyan
import com.farmbot.assistant.ui.theme.Line
import com.farmbot.assistant.ui.theme.Surface1
import com.farmbot.assistant.ui.theme.Surface2
import com.farmbot.assistant.ui.theme.TextPrimary
import com.farmbot.assistant.ui.theme.TextSecondary
import com.farmbot.assistant.ui.theme.TextTertiary
import com.farmbot.assistant.ui.theme.Warn
import kotlinx.coroutines.delay
import java.io.File

private val NAV_LABELS = listOf("农场", "规则", "日志", "设置")

/** 新版本红点 */
private val BadgeRed = Color(0xFFF04438)

/** 底部导航：和微信一样的分栏，点击切换页面；badge = 右上角有新版本红点 */
@Composable
fun BottomNav(selected: Int, onSelect: (Int) -> Unit, badge: Boolean = false) {
    Column(Modifier.fillMaxWidth().background(Surface1)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                NAV_LABELS.forEachIndexed { i, label ->
                    val active = i == selected
                    val tint = if (active) Cyan else TextTertiary
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(i) }
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(contentAlignment = Alignment.TopEnd) {
                            Canvas(Modifier.size(22.dp)) { drawNavGlyph(i, tint) }
                            // 「设置」上的新版本红点：不点不打扰，更新完自动消失
                            if (badge && i == NAV_LABELS.lastIndex) {
                                Box(
                                    Modifier
                                        .padding(top = 1.dp)
                                        .size(7.dp)
                                        .background(BadgeRed, CircleShape)
                                )
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            label,
                            color = tint,
                            fontSize = 11.sp,
                            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

/** 自绘四个图标，避免为了 4 个图形引入 material-icons 依赖 */
private fun DrawScope.drawNavGlyph(index: Int, color: Color) {
    val s = size.minDimension
    val stroke = Stroke(width = s * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (index) {
        // 农场：屋形
        0 -> {
            drawPath(
                Path().apply {
                    moveTo(s * 0.13f, s * 0.48f)
                    lineTo(s * 0.5f, s * 0.15f)
                    lineTo(s * 0.87f, s * 0.48f)
                },
                color, style = stroke
            )
            drawPath(
                Path().apply {
                    moveTo(s * 0.23f, s * 0.47f)
                    lineTo(s * 0.23f, s * 0.86f)
                    lineTo(s * 0.77f, s * 0.86f)
                    lineTo(s * 0.77f, s * 0.47f)
                },
                color, style = stroke
            )
        }
        // 规则：清单
        1 -> {
            for (i in 0..2) {
                val y = s * (0.22f + 0.28f * i)
                drawCircle(color, radius = s * 0.055f, center = Offset(s * 0.19f, y))
                drawLine(color, Offset(s * 0.36f, y), Offset(s * 0.85f, y), s * 0.085f, StrokeCap.Round)
            }
        }
        // 日志：纸张 + 行
        2 -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.2f, s * 0.11f),
                size = Size(s * 0.6f, s * 0.78f),
                cornerRadius = CornerRadius(s * 0.1f),
                style = stroke
            )
            for (i in 0..2) {
                val y = s * (0.32f + 0.17f * i)
                drawLine(color, Offset(s * 0.32f, y), Offset(s * 0.68f, y), s * 0.075f, StrokeCap.Round)
            }
        }
        // 设置：齿轮（齿 + 圆环 + 中心）
        else -> {
            drawCircle(color, radius = s * 0.25f, center = Offset(s * 0.5f, s * 0.5f), style = stroke)
            drawCircle(color, radius = s * 0.075f, center = Offset(s * 0.5f, s * 0.5f))
            for (i in 0 until 6) {
                val a = Math.toRadians(i * 60.0)
                val dx = kotlin.math.cos(a).toFloat()
                val dy = kotlin.math.sin(a).toFloat()
                drawLine(
                    color,
                    Offset(s * 0.5f + dx * s * 0.34f, s * 0.5f + dy * s * 0.34f),
                    Offset(s * 0.5f + dx * s * 0.44f, s * 0.5f + dy * s * 0.44f),
                    s * 0.09f,
                    StrokeCap.Round
                )
            }
        }
    }
}

/** 各页统一的标题区，和农场页 Header 的排版保持一致 */
@Composable
private fun PageHeader(title: String, subtitle: String, trailing: (@Composable () -> Unit)? = null) {
    val wide = isWideScreen()
    val pad = if (wide) 28.dp else 18.dp
    Row(
        modifier = Modifier
            .widthIn(max = ContentMaxWidth)
            .fillMaxWidth()
            .padding(horizontal = pad, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = TextSecondary, fontSize = 12.sp)
        }
        trailing?.invoke()
    }
}

private data class Rule(val key: String, val title: String, val desc: String)

private val RULES = listOf(
    Rule("rule_harvest", "一键务农", "一键种植、一键浇水、一键收获"),
    Rule("rule_plant", "补种空地块", "有空地时自动选种子播种"),
    Rule("rule_water", "浇水", "给缺水的地块浇水"),
    Rule("rule_weed", "除草除虫", "出现杂草/虫害时及时处理"),
    Rule("rule_steal", "拜访好友", "进入好友农场采摘可偷作物"),
    Rule("rule_daily", "每日任务", "领取农场每日任务与签到奖励"),
)

/** 一键务农详情页里的三个子功能 */
private data class HarvestPart(val key: String, val title: String, val desc: String)

private val HARVEST_PARTS = listOf(
    HarvestPart("rule_harvest_plant", "一键种植", "空地上自动选种子播种"),
    HarvestPart("rule_harvest_water", "一键浇水", "给缺水的地块自动浇水"),
    HarvestPart("rule_harvest_reap", "一键收获", "成熟作物一键收取"),
)

/** 规则页：进入农场之后，每个账号要按什么顺序做什么 */
@Composable
fun RulesPage() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("farmbot", android.content.Context.MODE_PRIVATE) }
    val checked = remember {
        mutableStateMapOf<String, Boolean>().apply {
            RULES.forEach { put(it.key, prefs.getBoolean(it.key, true)) }
        }
    }
    /** 点齿轮进入的详细设置页；null = 停留在规则列表 */
    var detail by remember { mutableStateOf<Rule?>(null) }
    val wide = isWideScreen()
    val pad = if (wide) 28.dp else 18.dp
    val enabledCount = checked.count { it.value }

    // 系统返回（侧边栏手势/返回键）先退回到规则列表，不要直接退出应用
    BackHandler(enabled = detail != null) { detail = null }

    val slideSpec = tween<IntOffset>(260)
    val fadeSpec = tween<Float>(220)
    AnimatedContent(
        targetState = detail,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            if (targetState != null) {
                // 进详情：详情页从右边滑进来，列表往左退一点，让人看清是「进去」了
                (slideInHorizontally(slideSpec) { it } + fadeIn(fadeSpec)) togetherWith
                    (slideOutHorizontally(slideSpec) { -it / 5 } + fadeOut(fadeSpec))
            } else {
                // 返回：详情往右滑走，列表从左边回到原位
                (slideInHorizontally(slideSpec) { -it / 5 } + fadeIn(fadeSpec)) togetherWith
                    (slideOutHorizontally(slideSpec) { it } + fadeOut(fadeSpec))
            }
        },
        label = "ruleDetail"
    ) { rule ->
        if (rule != null) {
            RuleDetailPage(
                rule = rule,
                enabled = checked[rule.key] == true,
                onToggle = { v ->
                    checked[rule.key] = v
                    prefs.edit().putBoolean(rule.key, v).apply()
                },
                onBack = { detail = null },
            )
            return@AnimatedContent
        }
        Column(
            modifier = Modifier.fillMaxSize().background(Bg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PageHeader("规则", "每个账号进入农场后要执行的操作") {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Cyan.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("$enabledCount/${RULES.size} 项", color = Cyan, fontSize = 12.sp)
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f).widthIn(max = ContentMaxWidth).fillMaxWidth(),
                contentPadding = PaddingValues(start = pad, end = pad, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(
                        "勾选的操作会对列表里的每个账号依次执行；顺序按农场页的账号排序。",
                        color = TextTertiary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                items(RULES, key = { it.key }) { rule ->
                    val on = checked[rule.key] == true
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Surface1)
                            .border(1.dp, Line, RoundedCornerShape(16.dp))
                            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(rule.title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(3.dp))
                            Text(rule.desc, color = TextSecondary, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = on,
                            onCheckedChange = { v ->
                                checked[rule.key] = v
                                prefs.edit().putBoolean(rule.key, v).apply()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF04262B),
                                checkedTrackColor = Cyan,
                                uncheckedThumbColor = TextTertiary,
                                uncheckedTrackColor = Surface2,
                                uncheckedBorderColor = Line,
                            )
                        )
                        // 齿轮：进入这一项的详细设置
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { detail = rule },
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(Modifier.size(17.dp)) { drawNavGlyph(3, TextTertiary) }
                        }
                    }
                }
            }
        }
    }
}

/** 单条规则的详细设置：启用状态 + 每轮最多执行次数 */
@Composable
private fun RuleDetailPage(
    rule: Rule,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("farmbot", android.content.Context.MODE_PRIVATE) }
    val wide = isWideScreen()
    val pad = if (wide) 28.dp else 18.dp
    var maxRound by remember { mutableStateOf(prefs.getInt("${rule.key}_max", 0)) }
    // 一键务农的三个子功能开关（其它规则用不到）
    val partChecked = remember {
        mutableStateMapOf<String, Boolean>().apply {
            HARVEST_PARTS.forEach { put(it.key, prefs.getBoolean(it.key, true)) }
        }
    }
    val shape = RoundedCornerShape(16.dp)

    Column(
        modifier = Modifier.fillMaxSize().background(Bg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = pad, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Text("‹", color = TextPrimary, fontSize = 22.sp)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(rule.title, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(rule.desc, color = TextSecondary, fontSize = 12.sp)
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).widthIn(max = ContentMaxWidth).fillMaxWidth(),
            contentPadding = PaddingValues(start = pad, end = pad, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (rule.key == "rule_harvest") {
                // 一键务农：详情页分成三个功能，各自独立开关
                items(HARVEST_PARTS, key = { it.key }) { part ->
                    val partOn = partChecked[part.key] ?: true
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(Surface1)
                            .border(1.dp, Line, shape)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(part.title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(3.dp))
                            Text(part.desc, color = TextSecondary, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = partOn,
                            onCheckedChange = { v ->
                                partChecked[part.key] = v
                                prefs.edit().putBoolean(part.key, v).apply()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF04262B),
                                checkedTrackColor = Cyan,
                                uncheckedThumbColor = TextTertiary,
                                uncheckedTrackColor = Surface2,
                                uncheckedBorderColor = Line,
                            )
                        )
                    }
                }
            } else {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(Surface1)
                            .border(1.dp, Line, shape)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("启用", color = TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Switch(
                            checked = enabled,
                            onCheckedChange = onToggle,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF04262B),
                                checkedTrackColor = Cyan,
                                uncheckedThumbColor = TextTertiary,
                                uncheckedTrackColor = Surface2,
                                uncheckedBorderColor = Line,
                            )
                        )
                    }
                }
            }
            // 一键务农的次数上限不展示（三个子功能还没接入执行引擎）
            if (rule.key != "rule_harvest") item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(Surface1)
                        .border(1.dp, Line, shape)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("每轮最多执行", color = TextPrimary, fontSize = 15.sp)
                        Spacer(Modifier.height(3.dp))
                        Text("0 = 不限，直到这一项做不了为止", color = TextSecondary, fontSize = 12.sp)
                    }
                    StepButton("−") {
                        maxRound = (maxRound - 1).coerceAtLeast(0)
                        prefs.edit().putInt("${rule.key}_max", maxRound).apply()
                    }
                    Text(
                        if (maxRound == 0) "不限" else "$maxRound 次",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(64.dp)
                    )
                    StepButton("+") {
                        maxRound = (maxRound + 1).coerceAtMost(50)
                        prefs.edit().putInt("${rule.key}_max", maxRound).apply()
                    }
                }
            }
            item {
                Text(
                    "这一项会作用到农场页里每个账号的每个区服；执行顺序按账号列表从上到下。",
                    color = TextTertiary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Surface2)
            .border(1.dp, Line, RoundedCornerShape(9.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = TextPrimary, fontSize = 16.sp)
    }
}

/** 日志页：直接读 bot.log 的尾部，每 2 秒自动刷新 */
@Composable
fun LogsPage() {
    val ctx = LocalContext.current
    val wide = isWideScreen()
    val pad = if (wide) 28.dp else 18.dp
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var refreshKey by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()

    fun reload() {
        lines = runCatching {
            val f = File(IO.dir(ctx, "logs"), "bot.log")
            if (f.exists()) f.readLines().takeLast(400) else emptyList()
        }.getOrElse { emptyList() }
    }

    LaunchedEffect(refreshKey) {
        while (true) {
            reload()
            delay(2000)
        }
    }

    // 有新日志时贴到底部
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Bg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PageHeader("日志", "最近 400 条运行记录 · 自动刷新") {
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Surface2)
                    .clickable { refreshKey++ }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("刷新", color = TextSecondary, fontSize = 12.sp)
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .padding(start = pad, end = pad, bottom = 20.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Surface1)
                .border(1.dp, Line, RoundedCornerShape(16.dp))
        ) {
            if (lines.isEmpty()) {
                Text(
                    "还没有运行日志",
                    color = TextTertiary,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    items(lines) { line ->
                        Text(
                            line,
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

/** 设置页：权限入口与关于信息 */
@Composable
fun SettingsPage(onOpenPerms: () -> Unit) {
    val ctx = LocalContext.current
    val wide = isWideScreen()
    val pad = if (wide) 28.dp else 18.dp
    val shape = RoundedCornerShape(16.dp)

    Column(
        modifier = Modifier.fillMaxSize().background(Bg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PageHeader("设置", "权限与应用信息")
        LazyColumn(
            modifier = Modifier.weight(1f).widthIn(max = ContentMaxWidth).fillMaxWidth(),
            contentPadding = PaddingValues(start = pad, end = pad, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(Surface1)
                        .border(1.dp, Line, shape)
                ) {
                    SettingRow("权限管理", "无障碍、悬浮窗、自启动、省电策略", onClick = onOpenPerms)
                }
            }
            item {
                // 有新版本：行内亮红点；已下载点一下直接安装，没下载完点一下重新检查
                val pending = Updater.latestTag != null
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(Surface1)
                        .border(1.dp, Line, shape)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (pending) {
                                    if (Updater.install(ctx)) Updater.dismissBanner()
                                } else {
                                    Updater.manualCheck(ctx)
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("检测更新", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                if (pending) {
                                    Spacer(Modifier.width(6.dp))
                                    Box(Modifier.size(7.dp).background(BadgeRed, CircleShape))
                                }
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(
                                Updater.status.ifBlank { "从 GitHub 检查新版本" },
                                color = TextSecondary, fontSize = 12.sp
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                    var autoOn by remember { mutableStateOf(Updater.autoEnabled(ctx)) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "自动检测更新",
                            color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = autoOn,
                            onCheckedChange = { v ->
                                autoOn = v
                                Updater.setAuto(ctx, v)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF04262B),
                                checkedTrackColor = Cyan,
                                uncheckedThumbColor = TextTertiary,
                                uncheckedTrackColor = Surface2,
                                uncheckedBorderColor = Line,
                            )
                        )
                    }
                }
            }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(Surface1)
                        .border(1.dp, Line, shape)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text("关于", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    InfoRow("应用", "王者农场助手")
                    InfoRow("包名", ctx.packageName)
                    InfoRow("版本", remember(ctx) {
                        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
                    })
                }
            }
            item {
                Text(
                    "本工具通过无障碍服务模拟操作，违反《王者荣耀用户协议》，存在封号风险，请自行评估。",
                    color = Warn.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, desc: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(3.dp))
            Text(desc, color = TextSecondary, fontSize = 12.sp)
        }
        Text("›", color = TextTertiary, fontSize = 18.sp)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.width(64.dp))
        Text(value, color = TextPrimary, fontSize = 13.sp)
    }
}

/**
 * 发现新版本的顶部小横幅：非模态，盖在页面最上沿，
 * 不遮挡屏幕中央、不打断正在执行的任务；用户点「稍后」只留红点。
 */
@Composable
fun UpdateBanner() {
    val ctx = LocalContext.current
    AnimatedVisibility(
        visible = Updater.bannerTag != null,
        enter = slideInVertically(tween(240)) { -it } + fadeIn(tween(240)),
        exit = slideOutVertically(tween(200)) { -it } + fadeOut(tween(200)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Surface2)
                .border(1.dp, Line, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "发现新版本 ${Updater.bannerTag ?: ""}",
                    color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium
                )
                val s = Updater.status
                if (s.isNotBlank()) {
                    Text(s, color = TextSecondary, fontSize = 12.sp)
                }
            }
            Text(
                "稍后",
                color = TextTertiary, fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { Updater.dismissBanner() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
            Text(
                "立即更新",
                color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        if (Updater.install(ctx)) Updater.dismissBanner()
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}
