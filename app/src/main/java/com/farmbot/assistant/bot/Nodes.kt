package com.farmbot.assistant.bot

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 基于无障碍节点树的点击能力。只用于系统设置这类标准界面 ——
 * 王者荣耀是 Unity 自绘，节点树里什么都没有，那里只能靠截图识别。
 */
object Nodes {

    private val SWITCH_CLASSES = setOf(
        "android.widget.Switch",
        "android.widget.CheckBox",
        "android.widget.ToggleButton",
        "android.widget.CompoundButton",
    )

    private const val MAX_NODES = 1200
    private const val MAX_DEPTH = 40

    /** 遍历整棵树，收集所有"像开关"的节点 */
    fun switchNodes(): List<AccessibilityNodeInfo> {
        val root = BotService.rootNode() ?: return emptyList()
        val out = ArrayList<AccessibilityNodeInfo>()
        walk(root, out, 0)
        return out
    }

    private fun walk(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (n == null || depth > MAX_DEPTH || out.size > MAX_NODES) return
        val cls = n.className?.toString().orEmpty()
        if (n.isCheckable || cls in SWITCH_CLASSES) out.add(n)
        for (i in 0 until n.childCount) {
            walk(n.getChild(i), out, depth + 1)
        }
    }

    /**
     * 点击「某一行里的那个开关」。
     * 判定方式：向上找 3 层父节点，把父节点子树里的所有文字拼起来，
     * 只要包含任一关键词就认为命中该行 —— 不依赖固定的层级和顺序。
     */
    fun clickSwitchInRow(keywords: List<String>, onlyIfOff: Boolean = true): Boolean {
        for (sw in switchNodes()) {
            if (onlyIfOff && sw.isChecked) continue
            val rowText = rowTextOf(sw)
            if (keywords.any { rowText.contains(it) }) {
                if (invokeClick(sw)) return true
            }
        }
        return false
    }

    /** 点击文字包含任一关键词的可点击节点（用于"允许""确定"这类弹窗） */
    fun clickByText(keywords: List<String>): Boolean {
        val root = BotService.rootNode() ?: return false
        val targets = ArrayList<AccessibilityNodeInfo>()
        collectClickable(root, targets, 0, keywords)
        for (t in targets) {
            if (invokeClick(t)) return true
        }
        return false
    }

    private fun collectClickable(
        n: AccessibilityNodeInfo?,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int,
        keywords: List<String>,
    ) {
        if (n == null || depth > MAX_DEPTH || out.size > 60) return
        val text = n.text?.toString().orEmpty()
        if (text.isNotEmpty() && keywords.any { text.contains(it) }) {
            if (n.isClickable) out.add(n)
            else n.parent?.let { if (it.isClickable) out.add(it) }
        }
        for (i in 0 until n.childCount) collectClickable(n.getChild(i), out, depth + 1, keywords)
    }

    private fun rowTextOf(node: AccessibilityNodeInfo): String {
        var cur: AccessibilityNodeInfo? = node
        var level = 0
        while (cur != null && level <= 3) {
            val sb = StringBuilder()
            gatherText(cur, sb, 0)
            if (sb.isNotEmpty()) return sb.toString()
            cur = cur.parent
            level++
        }
        return ""
    }

    private fun gatherText(n: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (n == null || depth > 6 || sb.length > 300) return
        n.text?.let { if (it.isNotEmpty()) sb.append(it).append(' ') }
        n.contentDescription?.let { if (it.isNotEmpty()) sb.append(it).append(' ') }
        for (i in 0 until n.childCount) gatherText(n.getChild(i), sb, depth + 1)
    }

    /** 找到文字包含关键词的节点边界，用于在"控件树不可见"的页面上做坐标兜底 */
    fun textBounds(keywords: List<String>): android.graphics.Rect? {
        val root = BotService.rootNode() ?: return null
        var found: android.graphics.Rect? = null
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 12 || found != null) return
            val t = n.text?.toString().orEmpty()
            if (t.isNotEmpty() && keywords.any { t.contains(it) }) {
                val r = android.graphics.Rect()
                n.getBoundsInScreen(r)
                if (r.width() > 0) {
                    found = r
                    return
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(root, 0)
        return found
    }

    /** 调试用：转储页面上带文字/可勾选的节点，定位"读不到开关"是哪一层出的问题 */
    fun classDump(maxChars: Int = 420): String {
        val root = BotService.rootNode() ?: return "no-root"
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 10 || sb.length > maxChars) return
            val cls = n.className?.toString()?.substringAfterLast('.') ?: "?"
            val t = n.text?.toString().orEmpty()
            if (t.isNotEmpty() || n.isCheckable) {
                sb.append(cls)
                if (n.isCheckable) sb.append("[chk=").append(n.isChecked).append(']')
                if (t.isNotEmpty()) sb.append(':').append(t.take(14))
                sb.append(" | ")
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(root, 0)
        return sb.toString()
    }

    private fun invokeClick(n: AccessibilityNodeInfo): Boolean {
        if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var p = n.parent
        var guard = 0
        while (p != null && guard < 3) {
            if (p.isClickable && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            p = p.parent
            guard++
        }
        return false
    }
}
