package io.github.soclear.edgex.hook

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.soclear.edgex.hook.util.afterAttach


object Ui {

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
