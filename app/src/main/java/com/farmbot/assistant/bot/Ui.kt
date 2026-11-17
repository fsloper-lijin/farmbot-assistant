package com.farmbot.assistant.bot

import com.farmbot.assistant.ocr.Ocr
import com.farmbot.assistant.ocr.Region

/**
 * 界面元素表：所有靠 OCR/控件树找的文字，集中在这里定义。
 *
 * 为什么要收拢：同一个「同意」以前在协议弹窗、QQ 授权页、微信授权页各写一遍，
 * 改一处漏两处；而且分不清哪处是「包含匹配」、哪处是「整行相等」。
 * 现在每个元素自带匹配方式，流程代码只写 `Ui.line(lines, Ui.AGREE_BTN)`。
 *
 * 只收「找元素用的文案」，不收流程内部的过滤规则（排除悬浮窗提示、
 * 过滤状态栏时间这类判断仍留在各自流程里，避免把逻辑藏进常量表）。
 */
object Ui {

    enum class Match {
        /** 行内包含任一关键词（气泡标题、带装饰符号的行都能命中） */
        CONTAINS,

        /** 去掉空白后整行等于关键词（按钮，避免命中正文里的同名词语） */
        EXACT,
    }

    data class Item(val keys: List<String>, val match: Match = Match.CONTAINS)

    private val WS = Regex("\\s+")

    // ---------------- 登录页 ----------------
    val QQ_LOGIN = Item(listOf("QQ好友玩"))
    val WX_LOGIN = Item(listOf("微信好友玩"))
    /** 登录页上进入扫码流程的入口 */
    val QR_AUTH_QQ = Item(listOf("QQ扫码授权"))
    val QR_AUTH_WX = Item(listOf("微信扫码授权"))
    val QR_PAGE_TITLE = Item(listOf("扫码授权登录"))
    val QR_MENU_QQ = Item(listOf("使用QQ手机版扫码"))
    val QR_MENU_WX = Item(listOf("使用微信扫码", "使用微信手机版扫码"))
    val GAME_START = Item(listOf("开始游戏"))
    val CHANGE_AREA = Item(listOf("换区"))

    // ---------------- 协议 / 系统弹窗 ----------------
    /** 协议弹窗正文标识（判断"这一屏是不是协议页"） */
    val AGREEMENT_TEXT = Item(listOf("协议", "许可", "隐私"))
    /** 底部的「同意」按钮 */
    val AGREE_BTN = Item(listOf("同意", "我同意", "同意并继续", "同意并进入"), Match.EXACT)
    /** 正文里带「同意」的行（只用于"页面上出现了同意字样"这种宽松判断） */
    val AGREE_ANY = Item(listOf("同意"))
    /** 协议弹窗底部与「同意」并列的按钮/标题 */
    val AGREEMENT_MARKERS = Item(listOf("拒绝", "许可及服务协议", "隐私政策", "隐私保护指引"))
    val REJECT = Item(listOf("拒绝"), Match.EXACT)
    val OPEN_APP = Item(listOf("想要打开", "启动应用", "是否允许"))
    val PERMISSION_DIALOG = Item(listOf("权限列表", "为保证正常体验"))
    val CONFIRM = Item(listOf("确定"), Match.EXACT)
    val UPDATE_POPUP = Item(listOf("更新公告", "亲爱的召唤师"))

    // ---------------- QQ 授权 ----------------
    val PICKER_PAGE = Item(listOf("选择要登录的账号", "添加账号"))
    val PICKER_TITLE = Item(listOf("选择要登录"))
    val SWITCH_ACCOUNT = Item(listOf("切换账号", "切换帐号"), Match.EXACT)
    /** 授权页一：平铺身份列表那一页 */
    val IDENTITY_PAGE = Item(listOf("使用不同", "和朋友关系"))
    val IDENTITY_SUBTITLE = Item(listOf("QQ头像昵称"))
    val CREATE_IDENTITY = Item(listOf("创建"), Match.EXACT)

    // ---------------- 微信授权 ----------------
    val WX_ALLOW = Item(listOf("允许", "确认登录", "登录"), Match.EXACT)

    // ---------------- 大厅 ----------------
    val EXIT = Item(listOf("退出"), Match.EXACT)

    // ---------------- 区域 ----------------
    /** 大厅右上角昵称：只在右上角这块 OCR，比整屏识别稳，也不会读到状态栏。
     *  纵向放宽到 0.26h：平板 21:9 上下黑边把游戏 UI 下移，昵称实测落在 0.183h。 */
    val NICKNAME_REGION = Region(0.70f, 0f, 1f, 0.26f)

    /** 在 OCR 结果里找第一个命中 [item] 的行 */
    fun line(lines: List<Ocr.ScanLine>, item: Item): Ocr.ScanLine? =
        lines.firstOrNull { matches(it.text, item) }

    /** 单行文字是否命中 [item]（比较前去掉空白，OCR 常把词拆出空格） */
    fun matches(text: String, item: Item): Boolean {
        val t = text.replace(WS, "")
        return item.keys.any { k -> if (item.match == Match.EXACT) t == k else t.contains(k) }
    }

    /** 页面上是否存在 [item]（OCR 结果） */
    fun has(lines: List<Ocr.ScanLine>, item: Item): Boolean = line(lines, item) != null

    /** 在控件树拼出来的窗口文字里找；节点文字之间是空格，所以只做包含匹配 */
    fun inText(text: String, item: Item): Boolean = item.keys.any { text.contains(it) }
}
