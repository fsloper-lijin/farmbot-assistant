package com.farmbot.assistant.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/** 大屏内容最大宽度：平板横屏下不让卡片被拉成一条长条 */
val ContentMaxWidth = 1200.dp

/** 底部主按钮最大宽度：平板上一整条约 1200dp 的按钮观感太差 */
val ActionMaxWidth = 560.dp

/** 平板/大屏判定（手机竖屏可用宽度一般 360–440dp，平板横屏 1000dp+） */
@Composable
fun isWideScreen(): Boolean = LocalConfiguration.current.screenWidthDp >= 600
