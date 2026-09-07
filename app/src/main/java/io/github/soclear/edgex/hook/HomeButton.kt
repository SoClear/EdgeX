package io.github.soclear.edgex.hook

import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.soclear.edgex.R
import io.github.soclear.edgex.hook.util.afterAttach
import java.util.WeakHashMap


object HomeButton {

    private class HomeButtonState : View.OnClickListener, View.OnLongClickListener {
        var originalClick: View.OnClickListener? = null
        var originalLongClick: View.OnLongClickListener? = null

        override fun onClick(view: View) {
            originalLongClick?.onLongClick(view)
        }

        override fun onLongClick(view: View): Boolean {
            originalClick?.onClick(view)
            return originalClick != null
        }
    }

    private const val EDGE_BOTTOM_NAV_BAR_LAYOUT_CLASS =
        "org.chromium.chrome.browser.edge_bottombar.EdgeBottomNavBarLayout"
    private const val EDGE_BOTTOM_BAR_PLUS_BUTTON_ID = "edge_bottom_bar_plus_button"
    private val homeButtonStates = WeakHashMap<View, HomeButtonState>()
    private var homeButtonListenerHooksInstalled = false

    private fun logHomeButton(message: String, throwable: Throwable? = null) {
        XposedBridge.log("[EdgeX][HomeButton] $message")
        throwable?.let(XposedBridge::log)
    }

    // 将 Plus 按钮替换为 Home 按钮
    fun replaceNewTabPageWithHome() = afterAttach {
        installHomeButtonListenerHooks()

        val bottomBarClass = XposedHelpers.findClassIfExists(
            EDGE_BOTTOM_NAV_BAR_LAYOUT_CLASS,
            classLoader
        ) ?: run {
            logHomeButton("未找到底栏布局: $EDGE_BOTTOM_NAV_BAR_LAYOUT_CLASS")
            return@afterAttach
        }

        XposedHelpers.findAndHookMethod(bottomBarClass, "onFinishInflate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val bottomBar = param.thisObject as? ViewGroup ?: return
                val buttonId = bottomBar.resources.getIdentifier(
                    EDGE_BOTTOM_BAR_PLUS_BUTTON_ID,
                    "id",
                    bottomBar.context.packageName
                )
                val plusButton = bottomBar.findViewById<View>(buttonId)
                if (buttonId == 0 || plusButton == null) {
                    logHomeButton("底栏已加载但未找到新标签页按钮")
                    return
                }
                registerNewTabButton(plusButton)
            }
        })
    }

    private fun installHomeButtonListenerHooks() {
        if (homeButtonListenerHooksInstalled) {
            return
        }
        homeButtonListenerHooksInstalled = true

        XposedHelpers.findAndHookMethod(
            View::class.java,
            "setOnClickListener",
            View.OnClickListener::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val button = param.thisObject as? View ?: return
                    val listener = param.args[0] as? View.OnClickListener
                    onNewTabButtonClickListenerChanged(button, listener)
                }
            }
        )
        XposedHelpers.findAndHookMethod(
            View::class.java,
            "setOnLongClickListener",
            View.OnLongClickListener::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val button = param.thisObject as? View ?: return
                    val listener = param.args[0] as? View.OnLongClickListener
                    onNewTabButtonLongClickListenerChanged(button, listener)
                }
            }
        )
    }

    private fun registerNewTabButton(button: View) {
        if (homeButtonStates.containsKey(button)) {
            return
        }
        homeButtonStates[button] = HomeButtonState()
        try {
            XposedHelpers.callMethod(button, "setImageResource", R.drawable.home)
        } catch (t: Throwable) {
            logHomeButton("替换 Home 图标失败: ${button.javaClass.name}", t)
        }
    }

    private fun onNewTabButtonClickListenerChanged(
        button: View,
        listener: View.OnClickListener?,
    ) {
        val state = homeButtonStates[button] ?: return
        if (listener !== state) {
            state.originalClick = listener
            applyHomeButtonListeners(button, state)
        }
    }

    private fun onNewTabButtonLongClickListenerChanged(
        button: View,
        listener: View.OnLongClickListener?,
    ) {
        val state = homeButtonStates[button] ?: return
        if (listener !== state) {
            state.originalLongClick = listener
            applyHomeButtonListeners(button, state)
        }
    }

    private fun applyHomeButtonListeners(button: View, state: HomeButtonState) {
        if (state.originalClick == null || state.originalLongClick == null) {
            return
        }

        button.setOnClickListener(state)
        button.setOnLongClickListener(state)
        button.isLongClickable = true
    }
}
