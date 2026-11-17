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

    // ------------------------------------------------------------------
    // 跨窗口操作：QQ/微信 授权小窗浮在游戏之上，不在 rootInActiveWindow 里。
    // 这些页面是标准原生控件（文本可精确读取），比 OCR 可靠得多。
    // ------------------------------------------------------------------

    /** 拼接所有无障碍窗口里的文字，用于判断小窗当前停在哪一页 */
    fun allWindowsText(): String {
        val sb = StringBuilder()
        for (root in BotService.windowRoots()) gatherTextNoLimit(root, sb, 0)
        return sb.toString()
    }

    /** 跨所有窗口收集带文字的节点（文字 + 屏幕边界），用于解析 QQ 授权页的身份列表 */
    fun allWindowsLines(): List<Pair<String, android.graphics.Rect>> {
        val out = ArrayList<Pair<String, android.graphics.Rect>>()
        for (root in BotService.windowRoots()) {
            fun walk(n: AccessibilityNodeInfo?, d: Int) {
                if (n == null || d > 25 || out.size > 400) return
                val t = n.text?.toString()?.trim().orEmpty()
                if (t.isNotEmpty()) {
                    val r = android.graphics.Rect()
                    n.getBoundsInScreen(r)
                    if (r.width() > 0 && r.height() > 0) out.add(t to r)
                }
                for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
            }
            walk(root, 0)
        }
        return out
    }

    /**
     * 跨所有窗口找可滚动节点并翻页（QQ 授权页身份列表一屏放不下时用）。
     * 只挑与 [areaTop, areaBottom] 有重叠的可滚动节点，按面积从小到大依次尝试
     * （面积最小＝层级最深，最可能是列表本体，而不是整个页面容器）。
     * 返回是否真的执行了滚动。
     */
    fun scrollAllWindows(forward: Boolean, areaTop: Int, areaBottom: Int): Boolean {
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val candidates = ArrayList<Pair<AccessibilityNodeInfo, Int>>()
        for (root in BotService.windowRoots()) {
            collectScrollable(root, 0) { n ->
                val r = android.graphics.Rect()
                n.getBoundsInScreen(r)
                val overlap = minOf(r.bottom, areaBottom) - maxOf(r.top, areaTop)
                if (overlap > 0 && r.width() > 0 && r.height() > 0) {
                    candidates.add(n to r.width() * r.height())
                }
            }
        }
        for ((node, _) in candidates.sortedBy { it.second }) {
            if (node.performAction(action)) return true
        }
        return false
    }

    private fun collectScrollable(
        n: AccessibilityNodeInfo?,
        depth: Int,
        hit: (AccessibilityNodeInfo) -> Unit,
    ) {
        if (n == null || depth > 25) return
        if (n.isScrollable) hit(n)
        for (i in 0 until n.childCount) collectScrollable(n.getChild(i), depth + 1, hit)
    }

    /** 跨所有窗口点击文字包含任一关键词的节点，返回被点击节点的文字 */
    fun clickByTextAllWindows(keywords: List<String>): String? {        for (root in BotService.windowRoots()) {
            val targets = ArrayList<AccessibilityNodeInfo>()
            collectByText(root, targets, 0, keywords)
            for (t in targets) {
                if (invokeClick(t)) return t.text?.toString() ?: keywords.first()
            }
        }
        // performAction 点不动（节点/祖先都不可点击）→ 按节点边界做坐标点击兜底
        val r = textBoundsAllWindows(keywords) ?: return null
        BotService.tap(r.centerX(), r.centerY())
        return "coord(${r.centerX()},${r.centerY()})"
    }

    /** 收集文字命中关键词的节点（不管自身是否 clickable，点击时再向上找可点祖先） */
    private fun collectByText(
        n: AccessibilityNodeInfo?,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int,
        keywords: List<String>,
    ) {
        if (n == null || depth > 25 || out.size > 80) return
        val t = (n.text?.toString() ?: n.contentDescription?.toString()).orEmpty()
        if (t.isNotEmpty() && keywords.any { t.contains(it) }) out.add(n)
        for (i in 0 until n.childCount) collectByText(n.getChild(i), out, depth + 1, keywords)
    }

    /** 跨所有窗口查找文字节点的屏幕边界（用于控件点不动时按坐标兜底） */
    fun textBoundsAllWindows(keywords: List<String>): android.graphics.Rect? {
        for (root in BotService.windowRoots()) {
            var found: android.graphics.Rect? = null
            fun walk(n: AccessibilityNodeInfo?, d: Int) {
                if (n == null || d > 20 || found != null) return
                val t = (n.text?.toString() ?: n.contentDescription?.toString()).orEmpty()
                if (t.isNotEmpty() && keywords.any { t.contains(it) }) {
                    val r = android.graphics.Rect()
                    n.getBoundsInScreen(r)
                    if (r.width() > 0) { found = r; return }
                }
                for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
            }
            walk(root, 0)
            if (found != null) return found
        }
        return null
    }

    private fun gatherTextNoLimit(n: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (n == null || depth > 25 || sb.length > 4000) return
        n.text?.let { if (it.isNotEmpty()) sb.append(it).append(' ') }
        n.contentDescription?.let { if (it.isNotEmpty()) sb.append(it).append(' ') }
        for (i in 0 until n.childCount) gatherTextNoLimit(n.getChild(i), sb, depth + 1)
    }

    /** 调试用：转储所有窗口的节点（类名/边界/文字/可点/可滚），排查 QQ 小窗的结构 */
    fun allWindowsDump(): String {
        val sb = StringBuilder()
        var index = 0
        for (root in BotService.windowRoots()) {
            index++
            sb.append("== window ").append(index).append(" ==\n")
            fun walk(n: AccessibilityNodeInfo?, d: Int) {
                if (n == null || d > 25 || sb.length > 16000) return
                val t = n.text?.toString()?.trim().orEmpty()
                val cd = n.contentDescription?.toString()?.trim().orEmpty()
                if (t.isNotEmpty() || cd.isNotEmpty() || n.isScrollable || n.isClickable) {
                    val r = android.graphics.Rect()
                    n.getBoundsInScreen(r)
                    repeat(d) { sb.append("  ") }
                    sb.append(n.className?.toString()?.substringAfterLast('.') ?: "?")
                        .append(" [").append(r.left).append(',').append(r.top).append(',')
                        .append(r.right).append(',').append(r.bottom).append(']')
                    if (n.isClickable) sb.append(" click")
                    if (n.isScrollable) sb.append(" scroll")
                    if (t.isNotEmpty()) sb.append(" text=").append(t)
                    if (cd.isNotEmpty()) sb.append(" desc=").append(cd)
                    sb.append('\n')
                }
                for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
            }
            walk(root, 0)
        }
        return sb.toString()
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
