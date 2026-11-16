package com.farmbot.assistant.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class LoginType(val label: String) {
    QQ("QQ 登录"),
    WECHAT("微信登录");

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
    val name: String = "",
    val loginType: LoginType = LoginType.QQ,
    val farms: List<Farm> = emptyList(),
    val enabled: Boolean = true,
) {
    val activeFarms: List<Farm> get() = farms.filter { it.enabled }
}

class AccountStore(private val ctx: Context) {

    private val file: File get() = File(ctx.filesDir, "accounts.json")

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
            loginType = LoginType.fromName(o.optString("loginType")),
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
                    put("loginType", a.loginType.name)
                    put("enabled", a.enabled)
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
