package io.github.soclear.edgex.hook

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.soclear.edgex.hook.util.afterAttach
import kotlin.math.roundToInt

object TabletToolbar {

    // 平板工具栏的运行时类；它本身没有重写 onFinishInflate，
    // 该方法由父类 ToolbarTablet 声明，因此 hook 目标是父类并按实例类型过滤
    private const val EDGE_TOOLBAR_TABLET_CLASS =
        "org.chromium.chrome.browser.toolbar.top.EdgeToolbarTablet"
    private const val TOOLBAR_TABLET_CLASS =
        "org.chromium.chrome.browser.toolbar.top.ToolbarTablet"
    private const val BOOKMARK_BAR_CLASS =
        "org.chromium.chrome.browser.bookmarks.bar.BookmarkBar"
    private const val BOOKMARK_BAR_BUTTON_CLASS =
        "org.chromium.chrome.browser.bookmarks.bar.BookmarkBarButton"

    // 需要保持隐藏的视图标识
    private val EXTRA_VIEW_NAMES = setOf(
        "edge_account_avatar",
        "edge_toolbar_copilot",
        "edge_toolbar_copilot_stub"
    )

    private fun log(message: String, throwable: Throwable? = null) {
        Log.w("EdgeX", "[TabletToolbar] $message", throwable)
        XposedBridge.log("[EdgeX][TabletToolbar] $message")
        throwable?.let(XposedBridge::log)
    }

    @SuppressLint("DiscouragedApi")
    private fun View.findViewByName(name: String): View? {
        val id = resources.getIdentifier(name, "id", context.packageName)
        return if (id != 0) findViewById(id) else null
    }

    private fun scalePx(value: Int, percent: Int): Int = (value * percent / 100f).roundToInt()

    /**
     * Edge 采用了 App Bundle (Isolated Split APKs)，核心 Chromium 代码存放在 split_chrome.apk 中。
     * 在 Application.attach 时 ClassLoader 默认仅包含 base.apk，需通过 split context 获取。
     */
    private fun Context.getChromeClassLoader(): ClassLoader {
        return try {
            createContextForSplit("chrome").classLoader
        } catch (t: Throwable) {
            log("获取 split_chrome classLoader 失败，回退到 base classLoader", t)
            classLoader
        }
    }

    /**
     * hook 平板工具栏的 inflate 时机
     */
    private fun hookToolbarTabletInflate(classLoader: ClassLoader, action: (ViewGroup) -> Unit): Boolean {
        val clazz = XposedHelpers.findClassIfExists(TOOLBAR_TABLET_CLASS, classLoader) ?: run {
            log("未找到平板工具栏基类: $TOOLBAR_TABLET_CLASS")
            return false
        }
        return try {
            XposedHelpers.findAndHookMethod(clazz, "onFinishInflate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.thisObject.javaClass.name != EDGE_TOOLBAR_TABLET_CLASS) return
                    try {
                        action(param.thisObject as ViewGroup)
                    } catch (t: Throwable) {
                        log("处理平板工具栏布局失败", t)
                    }
                }
            })
            true
        } catch (t: Throwable) {
            log("注册平板工具栏 hook 失败", t)
            false
        }
    }

    /**
     * 功能一：让平板端地址栏与电脑端样式大致同步
     * - 隐藏最左侧的用户头像
     * - 地址栏右侧的刷新移动到左侧“主页”之前
     * - 隐藏最右侧的 Copilot 聊天键
     * - 地址栏为 weight 布局，按钮腾出空间后自动加长
     */
    fun syncTabletToolbarWithDesktop() = afterAttach {
        val installed = hookToolbarTabletInflate(getChromeClassLoader()) { toolbar ->
            hideToolbarExtras(toolbar)
        }
        if (installed) {
            log("已注册平板工具栏同步 hook")
        }
    }

    private fun hideViewPermanently(view: View) {
        view.visibility = View.GONE
        view.layoutParams?.let { params ->
            if (params.width != 0 || params.height != 0) {
                params.width = 0
                params.height = 0
                if (params is ViewGroup.MarginLayoutParams) {
                    params.setMargins(0, 0, 0, 0)
                }
                view.layoutParams = params
            }
        }
    }

    private fun hideToolbarExtras(toolbar: ViewGroup) {
        val layout = toolbar.findViewByName("toolbar_tablet_layout") as? ViewGroup ?: run {
            log("未找到平板工具栏内部布局 toolbar_tablet_layout")
            return
        }

        // 1) 隐藏用户头像：设为 GONE 并清零宽高，防止占位
        val avatar = layout.findViewByName("edge_account_avatar")
        if (avatar != null) {
            hideViewPermanently(avatar)
            log("已隐藏用户头像")
        } else {
            log("未找到平板工具栏头像 edge_account_avatar")
        }

        // 2) 刷新移到“主页”之前（最终顺序：返回|前进|刷新|主页|地址栏）
        val refresh = layout.findViewByName("refresh_button")
        if (refresh != null) {
            val anchor = layout.findViewByName("home_button")
                ?: layout.findViewByName("location_bar")
            val params = refresh.layoutParams
            layout.removeView(refresh)
            val index = if (anchor != null) layout.indexOfChild(anchor) else layout.childCount
            layout.addView(refresh, index.coerceIn(0, layout.childCount), params)
            log("刷新按钮已移动到地址栏左侧 index=$index")
        } else {
            log("未找到平板工具栏刷新按钮 refresh_button")
        }

        // 3) 隐藏 Copilot 聊天键（布局里是 ViewStub 或已膨胀的 View）
        layout.findViewByName("edge_toolbar_copilot")?.let { hideViewPermanently(it) }
        layout.findViewByName("edge_toolbar_copilot_stub")?.let { hideViewPermanently(it) }

        // 4) 监听局部布局与层次变化，彻底杜绝全局 Hook View.setVisibility 的性能损耗
        installLayoutGuard(layout)
    }

    /**
     * 仅在当前 Toolbar 布局内监听布局重排，
     * 防止 Edge 异步将头像/Copilot 重新设为 VISIBLE 或 ViewStub 延迟膨胀。
     */
    private fun installLayoutGuard(layout: ViewGroup) {
        layout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            for (i in 0 until layout.childCount) {
                val child = layout.getChildAt(i)
                val name = try {
                    child.resources.getResourceEntryName(child.id)
                } catch (_: Exception) {
                    null
                }
                if (name in EXTRA_VIEW_NAMES && child.visibility != View.GONE) {
                    hideViewPermanently(child)
                }
            }
        }
    }

    /**
     * 功能二：按百分比调整收藏夹栏高度（重启 Edge 后生效，只缩小不放大）
     * 仅平板端打开“显示收藏夹栏”后适用：
     * 同步缩小收藏夹栏内容区、条目与两侧固定按钮，网页内容随之上移让出空间；
     * 点“→”展开的更多收藏弹窗保持 Edge 默认样式不变
     */
    fun scaleBookmarkBarHeight(percent: Int) = afterAttach {
        if (percent >= 100) {
            log("收藏夹栏高度设为 100%，保持默认尺寸不进行缩放")
            return@afterAttach
        }
        if (percent !in 50..99) {
            log("收藏夹栏高度百分比 $percent 超出有效范围 50..99，已忽略")
            return@afterAttach
        }
        applyBookmarkBarScaling(getChromeClassLoader(), percent)
    }

    /**
     * 收藏夹栏高度缩放：直接在 onFinishInflate 缩放内容区、条目与两侧固定按钮
     */
    private fun applyBookmarkBarScaling(classLoader: ClassLoader, percent: Int) {
        val barClass = XposedHelpers.findClassIfExists(BOOKMARK_BAR_CLASS, classLoader) ?: run {
            log("未找到收藏夹栏类: $BOOKMARK_BAR_CLASS")
            return
        }
        val buttonClass = XposedHelpers.findClassIfExists(BOOKMARK_BAR_BUTTON_CLASS, classLoader)

        try {
            XposedHelpers.findAndHookMethod(barClass, "onFinishInflate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val bar = param.thisObject as? ViewGroup ?: return
                        val content = bar.findViewByName("bookmark_bar_content_container") as? ViewGroup
                            ?: return
                        content.minimumHeight = scalePx(content.minimumHeight, percent)
                        content.setPadding(
                            content.paddingStart,
                            scalePx(content.paddingTop, percent),
                            content.paddingEnd,
                            scalePx(content.paddingBottom, percent)
                        )
                        for (name in listOf("bookmark_bar_overflow_button", "bookmark_bar_all_bookmarks_button")) {
                            val button = bar.findViewByName(name) ?: continue
                            val params = button.layoutParams ?: continue
                            if (params.width > 0) params.width = scalePx(params.width, percent)
                            if (params.height > 0) params.height = scalePx(params.height, percent)
                            button.layoutParams = params
                        }
                    } catch (t: Throwable) {
                        log("应用收藏夹栏高度缩放失败", t)
                    }
                }
            })
            log("已注册收藏夹栏高度缩放 hook: $percent%")
        } catch (t: Throwable) {
            log("注册收藏夹栏高度缩放 hook 失败", t)
        }

        if (buttonClass == null) return
        try {
            XposedHelpers.findAndHookMethod(buttonClass, "onFinishInflate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val button = param.thisObject as? ViewGroup ?: return
                        button.setPadding(
                            button.paddingStart,
                            scalePx(button.paddingTop, percent),
                            button.paddingEnd,
                            scalePx(button.paddingBottom, percent)
                        )
                        val icon = button.findViewByName("bookmark_bar_button_icon") ?: return
                        val params = icon.layoutParams ?: return
                        if (params.width > 0) params.width = scalePx(params.width, percent)
                        if (params.height > 0) params.height = scalePx(params.height, percent)
                        icon.layoutParams = params
                    } catch (t: Throwable) {
                        log("应用收藏按钮缩放失败", t)
                    }
                }
            })
        } catch (t: Throwable) {
            log("注册收藏按钮缩放 hook 失败", t)
        }
    }
}
