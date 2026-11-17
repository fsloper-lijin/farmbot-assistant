package com.farmbot.assistant.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 检测更新：进入软件检测一次，进程存活期间每小时轮询；发现新版立即后台下载。
 * 检测与下载都走 GitHub API：/releases/latest 的 JSON 同时给出版本号和附件直链，
 * 附件文件名不限（自动抓第一个 .apk）。直连与镜像并行竞速，谁先返回用谁的。
 */
object Updater {

    private const val REPO = "fsloper-lijin/farmbot-assistant"
    private const val HOUR = 60 * 60 * 1000L

    // shortcut: 公共镜像域名变动频繁，检测/下载全部失效时改这两张表即可
    /** 检测用的 API 前缀（直连 + 镜像），并行竞速 */
    private val API_PREFIXES = listOf(
        "https://api.github.com",
        "https://ghfast.top/https://api.github.com",
        "https://gh-proxy.com/https://api.github.com",
        "https://ghproxy.net/https://api.github.com",
    )

    /** 下载直链的前缀，空串 = GitHub 直连；并行竞速先到先得 */
    private val DL_PREFIXES = listOf(
        "",
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
    )

    /** GitHub 上的一次发布：版本号 + 第一个 APK 附件直链（可能没附 APK） */
    private class Rel(val tag: String, val apkUrl: String?)

    /** 发现到的新版本号（比当前版本新时才有值）：红点与横幅都看它 */
    var latestTag by mutableStateOf<String?>(null)
        private set

    /** 「检测更新」行的副文案 */
    var status by mutableStateOf("")
        private set

    /** 非空 = 顶部横幅显示该版本 */
    var bannerTag by mutableStateOf<String?>(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    @Volatile
    private var downloading = false

    /** 进入软件时调用一次：立刻检测，之后每小时一轮 */
    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        scope.launch {
            if (autoEnabled(app)) check(app, auto = true)
            while (true) {
                delay(HOUR)
                if (autoEnabled(app)) check(app, auto = true)
            }
        }
    }

    fun autoEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("auto_update_check", true)

    /** 开关「自动检测更新」：打开时立刻补检一次 */
    fun setAuto(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("auto_update_check", on).apply()
        if (on) scope.launch { check(ctx.applicationContext, auto = true) }
    }

    /** 手动点「检测更新」行 */
    fun manualCheck(ctx: Context) {
        status = "检查中…"
        scope.launch { check(ctx.applicationContext, auto = false) }
    }

    fun dismissBanner() {
        bannerTag = null
    }

    /** 拉起系统安装器；返回 true 表示已发起（横幅可以收起） */
    fun install(ctx: Context): Boolean {
        val tag = latestTag ?: return false
        val file = apkFile(ctx, tag)
        if (!file.exists()) {
            toast(ctx, "还在下载，请稍后再试")
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updater", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(intent) }
            .onFailure { toast(ctx, "无法拉起安装界面，请重试") }
            .isSuccess
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("farmbot", Context.MODE_PRIVATE)

    private fun apkFile(ctx: Context, tag: String) = File(ctx.cacheDir, "update/$tag.apk")

    private fun toast(ctx: Context, msg: String) =
        Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- 检测

    private suspend fun check(ctx: Context, auto: Boolean) {
        val (rel, reachable) = fetchRelease()
        if (rel == null) {
            // API 404 = 仓库还没发过版，别报成网络故障
            if (!auto) {
                status = if (reachable) "GitHub 上还没有发布版本" else "检查失败，请检查网络后重试"
            }
            return
        }
        val tag = rel.tag
        if (rel.apkUrl == null) {
            if (!auto) status = "发布 $tag 里没有 APK 附件"
            return
        }
        if (!newer(tag, currentVersion(ctx))) {
            latestTag = null
            if (!auto) status = "已是最新（v${currentVersion(ctx)}）"
            return
        }
        val first = latestTag == null
        latestTag = tag
        // 每小时轮到的那次只静默刷新红点；横幅每个进程只弹一次，不打扰
        if (!auto || first) {
            status = if (apkFile(ctx, tag).exists()) "已下载 $tag" else "发现 $tag，正在后台下载"
            if (auto) bannerTag = tag
        }
        download(ctx, tag, rel.apkUrl)
    }

    /**
     * API 直连 + 全部镜像同时检测：谁先拿到发布信息用谁的，慢源直接取消；
     * 「没发布过版本」要等全员表态再下结论，避免某个镜像的空响应盖住真实结果。
     */
    private suspend fun fetchRelease(): Pair<Rel?, Boolean> {
        val result = CompletableDeferred<Pair<Rel?, Boolean>>()
        val anyReachable = AtomicBoolean(false)
        val parent = SupervisorJob()
        val kids = API_PREFIXES.map { prefix ->
            scope.launch(parent) {
                val (rel, ok) = queryApi(prefix)
                if (rel != null) {
                    result.complete(rel to true)
                } else if (ok) {
                    anyReachable.set(true)
                }
            }
        }
        // 兜底：全员跑完都没有发布信息，按「有源通了」判定是否发布过版本
        scope.launch {
            kids.joinAll()
            result.complete(if (anyReachable.get()) null to true else null to false)
        }
        val res = result.await()
        parent.children.forEach { it.cancel() }
        return res
    }

    /** 单源检测：Pair(发布信息, 是否拿到明确响应)；404 = 明确还没发过版 */
    private fun queryApi(prefix: String): Pair<Rel?, Boolean> {
        try {
            val conn = URL("$prefix/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 8000
            conn.readTimeout = 12000
            // GitHub API 硬性要求带 User-Agent，缺失会 403
            conn.setRequestProperty("User-Agent", "farmbot-assistant")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val code = conn.responseCode
            if (code == 404) {
                conn.disconnect()
                return null to true
            }
            if (code !in 200..399) {
                conn.disconnect()
                return null to false
            }
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val obj = JSONObject(json)
            val tag = obj.optString("tag_name").trim()
            if (tag.isEmpty()) return null to true
            var apkUrl: String? = null
            val assets = obj.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apkUrl = a.optString("browser_download_url").ifBlank { null }
                        break
                    }
                }
            }
            return Rel(tag, apkUrl) to true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null to false
        }
    }

    private fun newer(tag: String, cur: String): Boolean {
        val a = tag.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        val b = cur.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun currentVersion(ctx: Context) = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    }.getOrDefault("0")

    // ---------------------------------------------------------------- 下载

    /**
     * 检测到就下载：API 给出的附件直链 + 各镜像前缀同时开跑，
     * 谁先下完用谁的，其余立刻取消；单飞，避免手动重试与自动下载撞车。
     */
    private fun download(ctx: Context, tag: String, apkUrl: String) {
        val file = apkFile(ctx, tag)
        if (file.exists() && file.length() > 0) {
            status = "已下载 $tag，点击安装"
            return
        }
        if (downloading) return
        downloading = true
        file.parentFile?.mkdirs()
        val winner = CompletableDeferred<Unit>()
        val parent = SupervisorJob()
        val pcts = ConcurrentHashMap<Int, Int>()
        DL_PREFIXES.forEachIndexed { i, prefix ->
            scope.launch(parent) {
                val tmp = File(file.parentFile, "${file.name}.$i.tmp")
                try {
                    val conn = URL(prefix + apkUrl)
                        .openConnection() as HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 20000
                    if (conn.responseCode != 200) {
                        throw IllegalStateException("HTTP ${conn.responseCode}")
                    }
                    val total = conn.contentLength.toLong()
                    var got = 0L
                    var lastPct = -1
                    conn.inputStream.use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                got += n
                                // 阻塞读之间靠它感知「别人已经赢了」的取消
                                ensureActive()
                                if (total > 0) {
                                    val pct = (got * 100 / total).toInt()
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        pcts[i] = pct
                                        status = "下载中 ${pcts.values.maxOrNull() ?: 0}%"
                                    }
                                }
                            }
                        }
                    }
                    conn.disconnect()
                    if (tmp.length() == 0L) throw IllegalStateException("empty apk")
                    if (winner.complete(Unit)) {
                        if (!tmp.renameTo(file)) throw IllegalStateException("rename failed")
                        status = "已下载 $tag，点击安装"
                        // 含自己在内全部取消；取消后没有挂起点，本协程照常跑完
                        parent.children.forEach { it.cancel() }
                    } else {
                        tmp.delete()
                    }
                } catch (e: CancellationException) {
                    tmp.delete()
                    throw e
                } catch (_: Exception) {
                    // 单个源挂了不影响其它源
                    tmp.delete()
                }
            }
        }
        scope.launch {
            parent.join()
            downloading = false
            if (file.exists() && file.length() > 0) {
                status = "已下载 $tag，点击安装"
            } else {
                file.parentFile?.listFiles()
                    ?.forEach { if (it.name.startsWith(file.name)) it.delete() }
                status = "下载失败，稍后会自动重试"
            }
        }
    }
}
