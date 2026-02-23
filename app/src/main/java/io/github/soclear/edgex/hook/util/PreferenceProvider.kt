package io.github.soclear.edgex.hook.util

import android.annotation.SuppressLint
import de.robv.android.xposed.XSharedPreferences
import io.github.soclear.edgex.BuildConfig
import io.github.soclear.edgex.data.Preference
import kotlinx.serialization.json.Json
import java.io.File

object PreferenceProvider {
    private const val PREFERENCE_FILE_NAME = "preference.json"

    val preference: Preference? = try {
        Json.decodeFromString<Preference>(getPreferenceFile().readText())
    } catch (_: Throwable) {
        null
    }

    fun getPreferenceFile(): File {
        val path = XSharedPreferences(BuildConfig.APPLICATION_ID).file.parent
        val file = File(path, PREFERENCE_FILE_NAME)
        if (!file.exists()) {
            file.writeText("{}")
            @SuppressLint("SetWorldReadable")
            file.setReadable(true, false)
        }
        return file
    }
}
