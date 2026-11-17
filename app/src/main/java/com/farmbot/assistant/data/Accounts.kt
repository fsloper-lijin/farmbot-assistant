package com.farmbot.assistant.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class LoginType(val label: String, val qr: Boolean) {
    QQ("QQ 登录", false),
    WECHAT("微信登录", false),
    QQ_QR("QQ 扫码登录", true),
    WECHAT_QR("微信扫码登录", true);

    companion object {
        fun fromName(n: String?): LoginType =
            entries.firstOrNull { it.name == n } ?: QQ
    }
}

/** 一个农场 = 某账号下某个区服角色的农场 */
data class Farm(
    val id: String = UUID.randomUUID().toString(),
    val server: String = "",
    val role: String = "",
    val level: Int = 0,
    val enabled: Boolean = true,
) {
    val title: String get() = if (server.isEmpty()) "未命名区服" else server
    val subtitle: String get() = buildString {
        if (role.isNotEmpty()) append(role)
        if (level > 0) {
            if (isNotEmpty()) append(" · ")
            append("Lv.").append(level)
        }
    }
}

/** 一个账号 = 一次独立的王者荣耀登录身份，可下辖多个区服农场 */
data class Account(
    val id: String = UUID.randomUUID().toString(),
    /** 游戏内昵称（大厅右上角显示的名字，可能是 QQ 账号下创建的登录身份，如「大号」「小号」） */
    val name: String = "",
    /** QQ 昵称（QQ 切号列表里显示的名字）。游戏身份名 ≠ QQ 昵称时用于选对 QQ 账号 */
    val qqNick: String = "",
    /** QQ 号：切号列表里 OCR 到的数字，也可以手动填；微信账号恒为空 */
    val qqNo: String = "",
    val loginType: LoginType = LoginType.QQ,
    /**
     * 该 QQ 账号下可选的游戏身份（QQ 授权页「你可以使用不同的头像/昵称登录」那张列表里的昵称）。
     * 第一个通常是 QQ 原始身份，后面是用「创建」做出来的分身。微信没有这个概念，恒为空。
     */
    val identities: List<String> = emptyList(),
    val farms: List<Farm> = emptyList(),
    val enabled: Boolean = true,
) {
    val activeFarms: List<Farm> get() = farms.filter { it.enabled }
}

class AccountStore(private val ctx: Context) {

    private val file: File get() = File(ctx.filesDir, "accounts.json")

    /** 是否已经有过账号数据（用来区分首次安装，首次安装要预置微信 + QQ 两张卡） */
    fun exists(): Boolean = file.exists()

    fun load(): List<Account> = runCatching {
        if (!file.exists()) {
            emptyList<Account>()
        } else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i -> parseAccount(arr.getJSONObject(i)) }
        }
    }.getOrElse { emptyList() }

    private fun parseAccount(o: JSONObject): Account {
        val farmsArr = o.optJSONArray("farms") ?: JSONArray()
        val farms = (0 until farmsArr.length()).map { i ->
            val f = farmsArr.getJSONObject(i)
            Farm(
                id = f.optString("id", UUID.randomUUID().toString()),
                server = f.optString("server", ""),
                role = f.optString("role", ""),
                level = f.optInt("level", 0),
                enabled = f.optBoolean("enabled", true),
            )
        }
        return Account(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name", ""),
            qqNick = o.optString("qqNick", ""),
            qqNo = o.optString("qqNo", ""),
            loginType = LoginType.fromName(o.optString("loginType")),
            identities = o.optJSONArray("identities")?.let { arr ->
                (0 until arr.length()).map { i -> arr.optString(i, "") }.filter { it.isNotEmpty() }
            } ?: emptyList(),
            farms = farms,
            enabled = o.optBoolean("enabled", true),
        )
    }

    fun save(list: List<Account>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { a ->
                arr.put(JSONObject().apply {
                    put("id", a.id)
                    put("name", a.name)
                    if (a.qqNick.isNotEmpty()) put("qqNick", a.qqNick)
                    if (a.qqNo.isNotEmpty()) put("qqNo", a.qqNo)
                    put("loginType", a.loginType.name)
                    put("enabled", a.enabled)
                    if (a.identities.isNotEmpty()) put("identities", JSONArray().apply {
                        a.identities.forEach { put(it) }
                    })
                    put("farms", JSONArray().apply {
                        a.farms.forEach { f ->
                            put(JSONObject().apply {
                                put("id", f.id)
                                put("server", f.server)
                                put("role", f.role)
                                put("level", f.level)
                                put("enabled", f.enabled)
                            })
                        }
                    })
                })
            }
            file.writeText(arr.toString())
        }
    }
}
