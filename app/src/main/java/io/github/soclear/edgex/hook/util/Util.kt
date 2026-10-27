package io.github.soclear.edgex.hook.util

import android.app.Activity
import android.app.AndroidAppHelper
import android.app.Application
import android.content.Context
import android.content.Context.CONTEXT_IGNORE_SECURITY
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.ParcelFileDescriptor
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.callStaticMethod
import de.robv.android.xposed.XposedHelpers.findAndHookMethod
import de.robv.android.xposed.XposedHelpers.findClass
import de.robv.android.xposed.XposedHelpers.findClassIfExists
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.io.File
import java.lang.reflect.Field

fun getSystemContext(): Context {
    val activityThreadClass = findClass("android.app.ActivityThread", null)
    val currentActivityThread = callStaticMethod(activityThreadClass, "currentActivityThread")
    return callMethod(currentActivityThread, "getSystemContext") as Context
}

fun Context.createCurrentContext(): Context = createPackageContext(
    AndroidAppHelper.currentPackageName(),
    CONTEXT_IGNORE_SECURITY
)

fun getCurrentSharedPreferences(name: String): SharedPreferences = getSystemContext()
    .createCurrentContext()
    .getSharedPreferences(name, MODE_PRIVATE)

fun LoadPackageParam.getSharedPreferences(name: String): SharedPreferences = getSystemContext()
    .createPackageContext(packageName, CONTEXT_IGNORE_SECURITY)
    .getSharedPreferences(name, MODE_PRIVATE)

fun getPackageVersionCode(name: String = AndroidAppHelper.currentPackageName()): Long =
    getSystemContext().packageManager.getPackageInfo(name, 0).longVersionCode

fun Context.getChromeContext(): Context {
    return try {
        createContextForSplit("chrome")
    } catch (_: Throwable) {
        this
    }
}

fun Context.getChromeClassLoader(): ClassLoader = getChromeContext().classLoader

fun afterAttach(action: Context.() -> Unit) {
    val callback = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val baseContext = param.args[0] as Context
            action(baseContext.getChromeContext())
        }
    }
    findAndHookMethod(Application::class.java, "attach", Context::class.java, callback)
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

private var cachedResourcesLoader: ResourcesLoader? = null

fun addAssetPath(modulePath: String) {
    val callback = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            if (param.hasThrowable()) return
            val context = param.thisObject as? Context ?: return
            try {
                val loader = cachedResourcesLoader ?: run {
                    val resourcesProvider = ParcelFileDescriptor.open(
                        File(modulePath), ParcelFileDescriptor.MODE_READ_ONLY
                    ).use { ResourcesProvider.loadFromApk(it) }
                    ResourcesLoader().apply {
                        addProvider(resourcesProvider)
                        cachedResourcesLoader = this
                    }
                }
                context.resources.addLoaders(loader)
            } catch (t: Throwable) {
                XposedBridge.log(t)
            }
        }
    }

    // ContextWrapper.attachBaseContext 返回时，Edge 的 attachBaseContext 覆写仍在执行。
    // 此时读取 Activity.resources 会使后续 applyOverrideConfiguration 抛出异常。
    // 等外层 attach 完整结束后再注入，确保语言、密度等配置已应用。
    findAndHookMethod(Application::class.java, "attach", Context::class.java, callback)
    XposedBridge.hookAllMethods(Activity::class.java, "attach", callback)
}

fun xlog(string: String) {
    val result = "\n\n////////////////\n\n////////////////\n\n$string\n\n////////////////\n\n"
    XposedBridge.log(result)
}
