package io.github.soclear.edgex.hook

import android.app.Activity
import android.content.Intent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage


object CustomTab {

    fun redirectCustomTab(loadPackageParam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.hookAllMethods(Activity::class.java, "onCreate", object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as Activity
                val className = activity.javaClass.getName()
                if ("org.chromium.chrome.browser.customtabs.CustomTabActivity" != className) {
                    return
                }
                val originalIntent = activity.intent ?: return
                val uri = originalIntent.data ?: return
                val scheme = uri.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") {
                    return
                }
                val cleanIntent = Intent(originalIntent).apply {
                    component = null
                    `package` = loadPackageParam.packageName
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    extras?.keySet()?.filter { key ->
                        key.startsWith("androidx.browser.customtabs.extra.") ||
                                key.startsWith("android.support.customtabs.extra.") ||
                                key.startsWith("org.chromium.chrome.browser.customtabs.")
                    }?.forEach(::removeExtra)
                    putExtra("com.android.browser.application_id", loadPackageParam.packageName)
                    putExtra("create_new_tab", true)
                }
                activity.startActivity(cleanIntent)
                activity.finish()
                originalIntent.data = null
            }
        })
    }
}
