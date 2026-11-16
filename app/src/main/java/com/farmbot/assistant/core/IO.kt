package com.farmbot.assistant.core

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object IO {

    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun dir(ctx: Context, name: String): File {
        val d = File(ctx.getExternalFilesDir(null), name)
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun savePng(bmp: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    fun log(ctx: Context, tag: String, msg: String) {
        val line = "${ts.format(Date())} [$tag] $msg"
        android.util.Log.i("FarmBot", line)
        runCatching {
            File(dir(ctx, "logs"), "bot.log").appendText(line + "\n")
        }
    }
}
