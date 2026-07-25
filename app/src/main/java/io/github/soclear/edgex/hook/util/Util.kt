package io.github.soclear.edgex.hook.util

import android.app.Application
import android.content.Context
import android.content.Context.CONTEXT_IGNORE_SECURITY
import android.content.Context.MODE_PRIVATE
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.ParcelFileDescriptor
import java.io.File
import java.lang.reflect.Field

fun getSystemContext(): Context {
    val activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null)
    val currentActivityThread =
        XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread")
    return XposedHelpers.callMethod(currentActivityThread!!, "getSystemContext") as Context
}

fun Context.createCurrentContext(): Context = createPackageContext(
    packageName,
    CONTEXT_IGNORE_SECURITY
)

fun getCurrentSharedPreferences(name: String): SharedPreferences = getSystemContext()
    .createCurrentContext()
    .getSharedPreferences(name, MODE_PRIVATE)

fun getPackageVersionCode(name: String = currentApplication()?.packageName.orEmpty()): Long =
    getSystemContext().packageManager.getPackageInfo(name, 0).longVersionCode

fun currentApplication(): Application? {
    val activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null)
    return XposedHelpers.callStaticMethod(activityThreadClass, "currentApplication") as? Application
}

fun afterAttach(action: Context.() -> Unit) {
    val callback = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            action(param.args[0] as Context)
        }
    }
    XposedHelpers.findAndHookMethod(
        Application::class.java,
        "attach",
        Context::class.java,
        callback
    )
}

val Class<*>.allFields: List<Field>
    get() {
        val fields = mutableListOf<Field>()
        var current: Class<*>? = this
        while (current != null) {
            fields.addAll(current.declaredFields)
            current = current.superclass
        }
        return fields
    }

fun addAssetPath(modulePath: String) {
    XposedHelpers.findAndHookMethod(
        ContextWrapper::class.java,
        "attachBaseContext",
        Context::class.java,
        object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val context = param.thisObject as Context
                if (context !is Application) return
                try {
                    val moduleApk = File(modulePath)
                    val parcelFileDescriptor = ParcelFileDescriptor.open(moduleApk, ParcelFileDescriptor.MODE_READ_ONLY)
                    val resourcesProvider = ResourcesProvider.loadFromApk(parcelFileDescriptor)
                    val resourcesLoader = ResourcesLoader()
                    resourcesLoader.addProvider(resourcesProvider)
                    context.resources.addLoaders(resourcesLoader)
                } catch (t: Throwable) {
                    XposedBridge.log(t)
                }
            }
        }
    )
}

fun xlog(string: String) {
    val result = "\n\n////////////////\n\n////////////////\n\n$string\n\n////////////////\n\n"
    XposedBridge.log(result)
}
