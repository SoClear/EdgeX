package io.github.soclear.edgex.hook

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Display
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.soclear.edgex.data.Preference
import io.github.soclear.edgex.hook.util.afterAttach
import kotlin.math.roundToInt


object Ui {

    @Suppress("DEPRECATION")
    fun setDpi(dpi: Int) {
        if (dpi !in Preference.DPI_RANGE) return

        val configurationHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val index = param.args.indexOfFirst { it is Configuration }
                if (index < 0) return
                val config = param.args[index] as Configuration
                if (config.densityDpi <= 0 || config.densityDpi == dpi) return

                // 同步 dp 尺寸，保持布局断点和资源选择与新密度一致。
                param.args[index] = Configuration(config).apply {
                    val scale = densityDpi.toFloat() / dpi
                    screenWidthDp = (screenWidthDp * scale).roundToInt()
                    screenHeightDp = (screenHeightDp * scale).roundToInt()
                    smallestScreenWidthDp = (smallestScreenWidthDp * scale).roundToInt()
                    densityDpi = dpi
                }
            }
        }
        val resourcesImpl = XposedHelpers.findClass("android.content.res.ResourcesImpl", null)
        XposedBridge.hookAllConstructors(resourcesImpl, configurationHook)
        XposedBridge.hookAllMethods(resourcesImpl, "updateConfiguration", configurationHook)

        // Chromium 在 Android 11 上直接读取 Display，需与资源密度保持一致。
        val metricsHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val metrics = param.args[0] as DisplayMetrics
                val density = dpi / DisplayMetrics.DENSITY_DEFAULT.toFloat()
                metrics.scaledDensity *= density / metrics.density
                metrics.density = density
                metrics.densityDpi = dpi
            }
        }
        for (method in listOf("getMetrics", "getRealMetrics")) {
            XposedHelpers.findAndHookMethod(
                Display::class.java, method, DisplayMetrics::class.java, metricsHook
            )
        }

        // Application 的资源可能早于 Hook 创建，在应用初始化前更新一次。
        XposedHelpers.findAndHookMethod(
            Application::class.java, "attach", Context::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val resources = (param.args[0] as Context).resources
                    resources.updateConfiguration(resources.configuration, resources.displayMetrics)
                }
            }
        )
    }

    /**
     * 移除 padding
     */
    fun removePadding(top: Boolean, bottom: Boolean) {
        if (!top && !bottom) return
        XposedHelpers.findAndHookMethod(
            View::class.java,
            "setPadding",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.thisObject.javaClass.name == "org.chromium.ui.edge_to_edge.layout.EdgeToEdgeBaseLayout") {
                        if (top) {
                            param.args[1] = 0
                        }
                        if (bottom) {
                            param.args[3] = 0
                        }
                    }
                }
            }
        )
    }

    /**
     * 设置新标签页 URL
     */
    fun setNewTabPageUrl(customUrl: String) = afterAttach {
        val loadUrlParamsClass = XposedHelpers.findClassIfExists(
            "org.chromium.content_public.browser.LoadUrlParams",
            classLoader
        ) ?: return@afterAttach

        XposedBridge.hookAllConstructors(loadUrlParamsClass, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val url = param.args.firstOrNull() as? String ?: return

                if (url == "chrome-native://newtab/" ||
                    url == "edge://newtab/" ||
                    url == "chrome://newtab/"
                ) {
                    param.args[0] = customUrl
                }
            }
        })
    }

    /**
     * 隐藏状态栏（沉浸）
     */
    fun hideStatusBar() {
        val hookLifecycle = object : XC_MethodHook() {
            @Suppress("DEPRECATION")
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as Activity
                val window = activity.window ?: return
                val decorView = window.decorView

                // 兼容 Android 11+ 新版 API (彻底沉浸)
                window.setDecorFitsSystemWindows(false)
                val controller = window.insetsController
                // 隐藏状态栏
                controller?.hide(WindowInsets.Type.statusBars())
                // 隐藏状态栏和导航栏
//                controller?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

                decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    .or(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
                    .or(View.SYSTEM_UI_FLAG_FULLSCREEN)
                    .or(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY )
//                隐藏导航栏
//                    .or(View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
//                    .or(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
            }
        }
        // Hook onCreate 和 onResume，防止 Edge 在后续流程中把状态栏拉出来
        XposedHelpers.findAndHookMethod(Activity::class.java, "onCreate", Bundle::class.java, hookLifecycle)
        XposedHelpers.findAndHookMethod(Activity::class.java, "onResume", hookLifecycle)
        XposedHelpers.findAndHookMethod(Activity::class.java, "onWindowFocusChanged", Boolean::class.javaPrimitiveType, hookLifecycle)
    }
}
