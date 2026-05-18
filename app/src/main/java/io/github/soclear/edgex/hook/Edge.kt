package io.github.soclear.edgex.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.AndroidAppHelper
import android.app.Dialog
import android.app.DownloadManager
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Browser
import android.text.TextUtils
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.URLUtil
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.net.toUri
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.soclear.edgex.MainViewModel
import io.github.soclear.edgex.MainViewModelFactory
import io.github.soclear.edgex.R
import io.github.soclear.edgex.data.DownloaderType
import io.github.soclear.edgex.hook.util.HookConfig
import io.github.soclear.edgex.hook.util.afterAttach
import io.github.soclear.edgex.hook.util.allFields
import io.github.soclear.edgex.hook.util.getHookConfig
import io.github.soclear.edgex.ui.MainScreen
import io.github.soclear.edgex.ui.theme.EdgeXTheme
import kotlinx.serialization.Serializable
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.text.DecimalFormat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt


object Edge {

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
     * 长按新建标签页按钮加载指定 url
     */
    fun setupLoadUrlOnLongClickNewTabButton(url: String) = afterAttach {
        val hookConfig = getHookConfig { getHookConfigFromDexKit() }
        if (hookConfig == null) {
            return@afterAttach
        }
        try {
            val methodThatCallNewTabButtonSetOnClickListener =
                DexMethod(hookConfig.methodThatCallNewTabButtonSetOnClickListener).getMethodInstance(
                    classLoader
                )

            val fieldNewTabButtonView =
                DexField(hookConfig.fieldNewTabButtonView).getFieldInstance(classLoader)

            val methodLoadUrl = DexMethod(hookConfig.methodLoadUrl)
                .getMethodInstance(classLoader)

            onLongClickNewTabButton(
                url,
                classLoader,
                methodThatCallNewTabButtonSetOnClickListener,
                hookConfig.fieldNameNewTabButtonActivityProvider,
                fieldNewTabButtonView,
                methodLoadUrl
            )
        } catch (_: Exception) {

        }
    }

    /**
     * 长按更多按钮回页面顶部
     */
    fun setupScrollToTopOnLongClickOverflowButton() = afterAttach {
        val hookConfig = getHookConfig { getHookConfigFromDexKit() }
        if (hookConfig == null) {
            return@afterAttach
        }
        try {
            val methodOverflowButtonOnLongClick =
                DexMethod(hookConfig.methodOverflowButtonOnLongClick).getMethodInstance(
                    classLoader
                )
            onLongClickOverflowButton(methodOverflowButtonOnLongClick)
        } catch (_: Exception) {

        }
    }


    // 长按新建标签页按钮加载指定 url，功能实现
    private fun onLongClickNewTabButton(
        url: String,
        classLoader: ClassLoader,
        methodThatCallNewTabButtonSetOnClickListener: Method,
        fieldNameNewTabButtonActivityProvider: String?,
        fieldNewTabButtonView: Field,
        methodLoadUrl: Method
    ) {
        XposedBridge.hookMethod(
            methodThatCallNewTabButtonSetOnClickListener, object : XC_MethodHook() {
                val loadUrlParams = XposedHelpers.findConstructorExact(
                    "org.chromium.content_public.browser.LoadUrlParams",
                    classLoader,
                    String::class.java,
                    Int::class.javaPrimitiveType
                ).newInstance(url, 0)

                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = fieldNewTabButtonView.get(param.thisObject) as View
                    view.setOnLongClickListener { _ ->
                        val activityTabProvider = fieldNameNewTabButtonActivityProvider?.let {
                            // 144.0.3719.91 (371909123) 及之后版本
                            XposedHelpers.getObjectField(param.thisObject, it)
                        } ?: param.args.last()

                        val tab = XposedHelpers.callMethod(activityTabProvider, "get")
                            ?: return@setOnLongClickListener true

                        methodLoadUrl(tab, loadUrlParams)
                        true
                    }
                }
            })
    }

    // 长按更多按钮回页面顶部，功能实现
    private fun onLongClickOverflowButton(methodOverflowButtonOnLongClick: Method) {
        XposedBridge.hookMethod(methodOverflowButtonOnLongClick, object : XC_MethodReplacement() {
            override fun replaceHookedMethod(param: MethodHookParam): Boolean {
                val clickedView = param.args[0] as View
                // 获取最顶层的视图，它是事件分发的最佳目标
                val rootView = clickedView.rootView ?: return true

                // 这是“移动到开头”的键码，对应键盘上的Home键
                val keyCode = KeyEvent.KEYCODE_MOVE_HOME

                // 1. 构造并分发 ACTION_DOWN 事件
                val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
                rootView.dispatchKeyEvent(downEvent)

                // 2. 构造并分发 ACTION_UP 事件
                val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
                rootView.dispatchKeyEvent(upEvent)

                return true
            }
        })
    }

    // 定义一个标记接口，用于防止循环替换
    private interface EdgeXSwappedListener

    // 将 Plus 按钮替换为 Home 按钮
    fun replaceNewTabPageWithHome() = afterAttach {
        val clazz = XposedHelpers.findClassIfExists("org.chromium.chrome.browser.edge_bottombar.BottomBarLayout", classLoader) ?: return@afterAttach
        val getListenerInfoMethod = XposedHelpers.findMethodExact(View::class.java, "getListenerInfo", *emptyArray<Any>())
        val listenerInfoClass = XposedHelpers.findClass($$"android.view.View$ListenerInfo", classLoader)
        val onClickField = XposedHelpers.findField(listenerInfoClass, "mOnClickListener")
        val onLongClickField = XposedHelpers.findField(listenerInfoClass, "mOnLongClickListener")

        XposedHelpers.findAndHookMethod(clazz, "onFinishInflate", object : XC_MethodHook() {
            @SuppressLint("DiscouragedApi")
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val bottomBar = param.thisObject as ViewGroup
                    val context = bottomBar.context
                    val plusButtonId = context.resources.getIdentifier("edge_bottom_bar_plus_button", "id", context.packageName)
                    if (plusButtonId == 0) return
                    val plusButton = bottomBar.findViewById<View>(plusButtonId) ?: return

                    // 1. 替换图标
                    if (plusButton is ImageView) {
                        plusButton.setImageResource(R.drawable.home)
                    } else {
                        XposedHelpers.callMethod(plusButton, "setImageResource", R.drawable.home)
                    }

                    // 2. 互换事件
                    var trueClick: View.OnClickListener? = null
                    var trueLongClick: View.OnLongClickListener? = null

                    plusButton.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
                        override fun onDraw() {
                            try {
                                // 【优化】直接使用原生的 invoke 和 get，极速读取内存变量
                                val listenerInfo = getListenerInfoMethod.invoke(plusButton) ?: return
                                val currentClick = onClickField.get(listenerInfo) as? View.OnClickListener
                                val currentLongClick = onLongClickField.get(listenerInfo) as? View.OnLongClickListener

                                if (currentClick != null && currentClick !is EdgeXSwappedListener) {
                                    trueClick = currentClick
                                }
                                if (currentLongClick != null && currentLongClick !is EdgeXSwappedListener) {
                                    trueLongClick = currentLongClick
                                }

                                val fullyHijacked = currentClick is EdgeXSwappedListener && currentLongClick is EdgeXSwappedListener

                                if (trueClick != null && trueLongClick != null && !fullyHijacked) {
                                    class SwappedClick : View.OnClickListener, EdgeXSwappedListener {
                                        override fun onClick(v: View) {
                                            trueLongClick.onLongClick(v)
                                        }
                                    }

                                    class SwappedLongClick : View.OnLongClickListener, EdgeXSwappedListener {
                                        override fun onLongClick(v: View): Boolean {
                                            trueClick.onClick(v)
                                            return true
                                        }
                                    }

                                    plusButton.setOnClickListener(SwappedClick())
                                    plusButton.setOnLongClickListener(SwappedLongClick())
                                }

                                if (trueClick != null && trueLongClick != null) {
                                    plusButton.isLongClickable = true
                                }

                            } catch (_: Throwable) {
                            }
                        }
                    })

                } catch (t: Throwable) {
                    XposedBridge.log(t)
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

    fun externalDownload(
        blockOriginalDownloadDialog: Boolean,
        setDefaultDownloader: Boolean,
        defaultDownloaderType: DownloaderType,
        defaultDownloaderPackageName: String
    ) = afterAttach {
        var topActivityRef: WeakReference<Activity>? = null

        XposedHelpers.findAndHookMethod(
            Activity::class.java,
            "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    topActivityRef = WeakReference(param.thisObject as Activity)
                }
            }
        )

        val shouldBlockDialog = AtomicBoolean(false)
        val myDialogKey = "EdgeX"

        if (blockOriginalDownloadDialog) {
            XposedHelpers.findAndHookMethod(
                Dialog::class.java,
                "show", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val isMyDialog = XposedHelpers.getAdditionalInstanceField(
                            param.thisObject,
                            myDialogKey
                        ) as? Boolean ?: false
                        if (!isMyDialog && shouldBlockDialog.get()) {
                            param.result = null
                        }
                    }
                }
            )
        }

        val downloadManagerService = XposedHelpers.findClassIfExists(
            "org.chromium.chrome.browser.download.DownloadManagerService",
            classLoader
        ) ?: return@afterAttach

        XposedHelpers.findAndHookMethod(
            downloadManagerService,
            "onDownloadItemCreated",
            "org.chromium.chrome.browser.download.DownloadItem",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val downloadItem = param.args[0] ?: return

                        // 从 DownloadItem.c 字段提取 DownloadInfo 对象
                        val downloadInfo = XposedHelpers.getObjectField(downloadItem, "c") ?: return
                        // 字段 v 为 0 表示任务处于 Starting/Pending 状态
                        val downloadState = XposedHelpers.getIntField(downloadInfo, "v")
                        // 只保留新建的下载
                        if (downloadState != 0) return
                        val mimeType = XposedHelpers.getObjectField(downloadInfo, "c") as String?
                        // 排除插件
                        if (mimeType == "application/x-chrome-extension") return
                        val activity = topActivityRef?.get() ?: return
                        val isValidActivity = !activity.isFinishing && !activity.isDestroyed
                        // 如果获取不到活着的 Activity，尝试使用全局 ApplicationContext
                        val context: Context = (if (isValidActivity) activity else AndroidAppHelper.currentApplication()) ?: return

                        if (blockOriginalDownloadDialog) {
                            // 主动取消 Edge 的内部下载，防止弹出通知
                            XposedHelpers.callMethod(
                                param.thisObject,
                                "broadcastDownloadAction",
                                downloadItem,
                                "org.chromium.chrome.browser.download.DOWNLOAD_CANCEL"
                            )

                            // 阻止 Edge 继续处理该下载，从而屏蔽其通知和列表更新
                            param.result = null
                            // 禁止所有弹窗
                            shouldBlockDialog.set(true)
                            // 2秒后启用弹窗
                            Handler(Looper.getMainLooper()).postDelayed({
                                shouldBlockDialog.set(false)
                            }, 2000)
                        }

                        // URL 是 GURL 对象，需要调用 .j() 获取字符串
                        val gurlUrl = XposedHelpers.getObjectField(downloadInfo, "a")
                        val url = XposedHelpers.callMethod(gurlUrl, "j") as String

                        val userAgent = XposedHelpers.getObjectField(downloadInfo, "b") as String?
                        val cookie = XposedHelpers.getObjectField(downloadInfo, "d") as String?
                        // 因为在下载任务刚创建时，文件名为空。
//                        val fileName = XposedHelpers.getObjectField(downloadInfo, "e") as String?
                        // 所以从 url 中提取文件名
                        val fileName = URLUtil.guessFileName(url, null, mimeType)

                        val gurlReferrer = XposedHelpers.getObjectField(downloadInfo, "h")
                        val referrer = XposedHelpers.callMethod(gurlReferrer, "j") as String?
                        val totalBytes = XposedHelpers.getLongField(downloadInfo, "k")
                        if (setDefaultDownloader) {
                            if (defaultDownloaderType == DownloaderType.SYSTEM_DOWNLOADER) {
                                systemDownload(
                                    url,
                                    cookie,
                                    userAgent,
                                    referrer,
                                    mimeType,
                                    fileName,
                                    context
                                )
                            } else {
                                thirdPartyDownload(
                                    url,
                                    mimeType,
                                    cookie,
                                    userAgent,
                                    referrer,
                                    context,
                                    defaultDownloaderPackageName
                                )
                            }
                        } else {
                            showExternalDownloadDialog(
                                activity,
                                fileName,
                                totalBytes,
                                url,
                                cookie,
                                userAgent,
                                referrer,
                                mimeType
                            )
                        }
                    } catch (t: Throwable) {
                        XposedBridge.log(t)
                    }
                }

                private fun showExternalDownloadDialog(
                    activity: Activity,
                    fileName: String?,
                    totalBytes: Long,
                    url: String,
                    cookie: String?,
                    userAgent: String?,
                    referrer: String?,
                    mimeType: String?
                ) {
                    Handler(Looper.getMainLooper()).post {
                        AlertDialog.Builder(activity)
                            .setTitle(fileName)
                            .setView(createUrlContainer(activity, totalBytes, url))
                            .setPositiveButton(getString(R.string.download_system)) { _, _ ->
                                systemDownload(
                                    url,
                                    cookie,
                                    userAgent,
                                    referrer,
                                    mimeType,
                                    fileName,
                                    activity
                                )
                            }
                            .setNegativeButton(getString(R.string.download_third_party)) { _, _ ->
                                thirdPartyDownload(
                                    url,
                                    mimeType,
                                    cookie,
                                    userAgent,
                                    referrer,
                                    activity,
                                    null
                                )
                            }
                            .setNeutralButton(getString(R.string.download_copy_link)) { _, _ ->
                                copyLink(activity, url)
                            }
                            .setOnDismissListener {
                                closeBlankTab(activity)
                            }
                            .create()
                            .also {
                                if (blockOriginalDownloadDialog) {
                                    XposedHelpers.setAdditionalInstanceField(
                                        it,
                                        myDialogKey,
                                        true
                                    )
                                }
                            }
                            .show()
                    }
                }

                private fun createUrlContainer(
                    activity: Activity,
                    totalBytes: Long,
                    url: String
                ): LinearLayout {
                    val padding =
                        (20 * activity.resources.displayMetrics.density).roundToInt()
                    val container = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(padding, padding / 2, padding, 0)
                    }

                    container.addView(TextView(activity).apply {
                        text = formatFileSize(totalBytes)
                    })

                    container.addView(TextView(activity).apply {
                        text = Uri.decode(url)
                        setPadding(
                            0,
                            (8 * activity.resources.displayMetrics.density).roundToInt(),
                            0,
                            0
                        )
                        maxLines = 5
                        ellipsize = TextUtils.TruncateAt.END
                        setTextIsSelectable(true)
                    })
                    return container
                }


                fun formatFileSize(size: Long): String {
                    if (size <= 0) return "0 B"

                    val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB", "EB")
                    // 计算单位所在的索引 (通过对数计算，性能优于循环除法)
                    val digitGroups =
                        (log10(size.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.size - 1)

                    // 计算得出具体数值
                    val value = size / 1024.0.pow(digitGroups.toDouble())

                    // 格式化输出：最多保留两位小数，如果末尾是0会自动省略 (例如展示为 1.5 MB 而不是 1.50 MB)
                    val decimalFormat = DecimalFormat("#,##0.##")
                    return "${decimalFormat.format(value)} ${units[digitGroups]}"
                }

                private fun systemDownload(
                    url: String,
                    cookie: String?,
                    userAgent: String?,
                    referrer: String?,
                    mimeType: String?,
                    fileName: String?,
                    context: Context
                ) {
                    val uri = url.toUri()
                    val request = DownloadManager.Request(uri).apply {
                        if (!cookie.isNullOrBlank()) addRequestHeader(
                            "Cookie",
                            cookie
                        )
                        if (!userAgent.isNullOrBlank()) addRequestHeader(
                            "User-Agent",
                            userAgent
                        )
                        if (!referrer.isNullOrBlank()) addRequestHeader(
                            "Referer",
                            referrer
                        )
                        if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                        setTitle(fileName)
                        setDescription(getString(R.string.download_downloading))
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS,
                            fileName
                        )
                        setAllowedOverMetered(true)
                        setAllowedOverRoaming(true)
                    }

                    val downloadManager =
                        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    downloadManager.enqueue(request)
                }

                private fun thirdPartyDownload(
                    url: String,
                    mimeType: String?,
                    cookie: String?,
                    userAgent: String?,
                    referrer: String?,
                    context: Context,
                    targetPackageName: String?
                ) {
                    try {
                        val uri = url.toUri()
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                            // 安全地设置 Uri 和 MimeType
                            if (!mimeType.isNullOrBlank()) {
                                setDataAndType(uri, mimeType)
                            } else {
                                // 如果没有 mimeType，仅设置 uri
                                data = uri
                            }

                            val headers = Bundle().apply {
                                if (!cookie.isNullOrBlank()) putString(
                                    "Cookie",
                                    cookie
                                )
                                if (!userAgent.isNullOrBlank()) putString(
                                    "User-Agent",
                                    userAgent
                                )
                                if (!referrer.isNullOrBlank()) putString(
                                    "Referer",
                                    referrer
                                )
                            }
                            putExtra(Browser.EXTRA_HEADERS, headers)
                        }

                        if (targetPackageName.isNullOrBlank()) {
                            val chooser =
                                Intent.createChooser(
                                    intent,
                                    getString(R.string.download_chooser_title)
                                )
                            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(chooser)
                        } else {
                            // 指定了包名
                            intent.setPackage(targetPackageName)
                            val pm = context.packageManager

                            // 局部函数：寻找目标并用 ApplicationContext 强制启动
                            val tryForceLaunch = { testIntent: Intent ->
                                var isLaunched = false

                                // 使用 queryIntentActivities 获取所有能处理该 Intent 的 Activity
                                @SuppressLint("QueryPermissionsNeeded")
                                val resolveInfos = pm.queryIntentActivities(testIntent, 0)
                                // 从中筛选出真正属于目标包名（例如 IDM+）的 Activity
                                val targetInfo = resolveInfos.find { it.activityInfo.packageName == targetPackageName }

                                if (targetInfo != null) {
                                    // 找到了目标应用内的真实 Activity，强制转为显式 Intent
                                    testIntent.component = ComponentName(
                                        targetInfo.activityInfo.packageName,
                                        targetInfo.activityInfo.name
                                    )
                                    try {
                                        context.applicationContext.startActivity(testIntent)
                                        isLaunched = true
                                    } catch (e: Exception) {
                                        XposedBridge.log("Explicit launch failed: ${e.stackTraceToString()}")
                                    }
                                }

                                // 如果找不到具体的 Activity（可能是受到 Android 11+ 包可见性限制）
                                // 或者上面的启动失败了，我们尝试清除 Component 并直接依赖 setPackage 启动
                                if (!isLaunched) {
                                    try {
                                        // 极其重要：清除可能残留的错误 component（防止携带系统的 ResolverActivity）
                                        testIntent.component = null
                                        testIntent.setPackage(targetPackageName)
                                        context.applicationContext.startActivity(testIntent)
                                        isLaunched = true
                                    } catch (e: Exception) {
                                        XposedBridge.log("Implicit launch failed: ${e.stackTraceToString()}")
                                    }
                                }

                                isLaunched
                            }

                            // 1. 精确匹配
                            var isLaunched = tryForceLaunch(intent)

                            if (!isLaunched) {
                                // 2. 降级匹配: */*
                                intent.setDataAndType(uri, "*/*")
                                isLaunched = tryForceLaunch(intent)
                            }

                            if (!isLaunched) {
                                // 3. 降级匹配: 只保留 URL
                                intent.data = uri
                                isLaunched = tryForceLaunch(intent)
                            }

                            if (!isLaunched) {
                                Toast.makeText(
                                    context,
                                    getString(R.string.download_target_app_failed),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(
                            context,
                            getString(R.string.download_no_app_found),
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (t: Throwable) {
                        XposedBridge.log(t)
                    }
                }

                private fun copyLink(activity: Activity, url: String) {
                    val clipboard =
                        activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText(
                        getString(R.string.download_clipboard_label),
                        url
                    )
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(
                        activity,
                        getString(R.string.download_copied_toast),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                private fun closeBlankTab(activity: Activity) {
                    // 下面代码只是为了关闭因为下载而产生的空白标签页
                    // 而原版下载会自动关闭
                    if (!blockOriginalDownloadDialog) return
                    val currentTab = try {
                        val key = "ActivityTabProviderField"

                        val activityTabProviderField =
                            XposedHelpers.getAdditionalStaticField(
                                Unit,
                                key
                            ) as Field?
                                ?: run {
                                    activity.javaClass.allFields.find {
                                        it.type.name == "org.chromium.chrome.browser.ActivityTabProvider"
                                    }?.also {
                                        XposedHelpers.setAdditionalStaticField(
                                            Unit,
                                            key,
                                            it
                                        )
                                    }
                                } ?: return

                        activityTabProviderField.isAccessible = true
                        val activityTabProvider =
                            activityTabProviderField.get(activity)
                        XposedHelpers.callMethod(activityTabProvider, "get")
                    } catch (t: Throwable) {
                        XposedBridge.log(t)
                        null
                    } ?: return


                    val gurl = XposedHelpers.callMethod(currentTab, "getUrl")
                    val currentUrl = XposedHelpers.callMethod(gurl, "j") as? String
                    // about:blank
                    if (currentUrl == "") {
                        val activityName = activity.javaClass.name
                        if (activityName.contains("CustomTabActivity")) {
                            activity.finish()
                        } else if (activityName.contains("ChromeTabbedActivity")) {
                            XposedHelpers.callStaticMethod(
                                XposedHelpers.findClass(
                                    "org.chromium.chrome.browser.tab.TabImpl",
                                    classLoader
                                ),
                                "closeTabFromNative",
                                currentTab
                            )
                        }
                    }
                }
            }
        )
    }

    /**
     * clearBrowsingData：退出时自动清除浏览数据
     *
     * @param dataTypes：这个 0 ~ 5 数组完全对应了 Chromium 内核（也是 Edge 清除浏览数据面板）的经典 BrowsingDataType 枚举结构定义，具体对应关系如下：
     * 0：History（浏览历史记录）
     * 1：Cache（缓存的图片和网页文件）
     * 2：Cookies & Site Data（Cookie、本地存储和网站数据）
     * 3：Passwords（保存的密码）
     * 4：Form Data（自动填充的表单数据，包括地址、姓名等）
     * 5：Site Settings（针对具体网站的权限设置，比如麦克风/摄像头授权）
     *
     * @param shouldClearTabs: 是否清除浏览器的标签页
     *
     * @param timePeriod：在基于 Chromium 内核的 Edge 和 Chrome 浏览器中，清除数据的时间范围（TimePeriod）对应着系统底层的 C++ 枚举变量 browsing_data::TimePeriod。
     * 它通常包含以下几个固定的可选常量值：
     * 0: 过去 1 小时 (Last hour)
     * 1: 过去 24 小时 (Last 24 hours)
     * 2: 过去 7 天 (Last 7 days)
     * 3: 过去 4 周 (Last 4 weeks)
     * 4: 时间不限 / 全部时间 (All time) —— 这是清空的最强指令，也就是您刚才所用的。
     * 5: 30 天前的数据 (Older than 30 days) —— 内部选项，通常用于部分浏览器的自动闲置清理计划。
     * 6: 过去 15 分钟 (Last 15 minutes) —— 这是近一两年 Chromium 内核新增的一个用于快速抹除极短期隐私痕迹的选项（在部分快捷控制面板中可见）。
     * */
    fun clearBrowsingDataOnExit(dataTypes: IntArray, shouldClearTabs: Boolean, timePeriod: Int) {
        // 1. Hook Activity 的 onCreate，在应用处于前台时，主动启动占坑 Service
        XposedHelpers.findAndHookMethod(Activity::class.java, "onCreate", Bundle::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as Activity
                // 只在主界面的 Activity 里启动即可，避免每个 Activity 都调
                if (activity.javaClass.name.contains("ChromeTabbedActivity")) {
                    try {
                        val intent = Intent()
                        intent.setClassName(activity, "androidx.browser.customtabs.PostMessageService")
                        activity.startService(intent)
                    } catch (e: Exception) {
                        XposedBridge.log("启动占坑 Service 失败: ${e.stackTraceToString()}")
                    }
                }
            }
        })

        // 2. onTaskRemoved Hook（当系统检测到卡片被划走，就会通过占坑 Service 回调这里）
        XposedHelpers.findAndHookMethod(Service::class.java, "onTaskRemoved", Intent::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val service = param.thisObject as Service
                // 我们只在这个特定的占坑 Service 收到划除事件时处理，避免影响其他
                if (!service.javaClass.name.contains("PostMessageService")) {
                    return
                }
                // ✅ onTaskRemoved 被成功触发！应用正在被划掉，开始执行清除逻辑")
                try {
                    // 关键1：安全地获取当前 App 的真正的 ClassLoader，防止外部闭包传参带来的类加载器不匹配
                    val appClassLoader = service.applicationContext.classLoader
                    val profileManagerClass = XposedHelpers.findClassIfExists("org.chromium.chrome.browser.profiles.ProfileManager", appClassLoader) ?: return
                    val profile = XposedHelpers.callStaticMethod(profileManagerClass, "b") ?: return
                    val bridgeClass = XposedHelpers.findClass("org.chromium.chrome.browser.browsing_data.BrowsingDataBridge", appClassLoader)
                    val bridge = XposedHelpers.callStaticMethod(bridgeClass, "b", profile) ?: return
                    val listenerClass = XposedHelpers.findClass($$"org.chromium.chrome.browser.browsing_data.BrowsingDataBridge$OnClearBrowsingDataListener", appClassLoader)

                    // 核心清除方法
                    XposedHelpers.callMethod(bridge, "a", arrayOf(listenerClass, IntArray::class.javaPrimitiveType, Int::class.javaPrimitiveType), null, dataTypes, timePeriod)

                    if (shouldClearTabs) {
                        // ========= 物理抹除全部标签页记忆 =========
                        try {
                            val dataDir = service.applicationInfo.dataDir

                            // 现代版 Edge (基于新版 Chromium) 标签页核心存储路径
                            val edgeTabsDir = java.io.File(dataDir, "app_chrome/Default/tabs")
                            if (edgeTabsDir.exists()) {
                                edgeTabsDir.deleteRecursively()
                            }
                            // 强力增强：新版 Chromium 可能会通过 Sessions 恢复历史卡片和分组状态，顺手斩草除根
                            val sessionsDir = java.io.File(dataDir, "app_chrome/Default/Sessions")
                            if (sessionsDir.exists()) {
                                sessionsDir.deleteRecursively()
                            }
                        } catch (e: Exception) {
                            XposedBridge.log("❌ 物理清除标签页文件失败: ${e.stackTraceToString()}")
                        }
                    }

                    // 关键2：给进程暴力续命一小会儿
                    // 原因：Chromium 的 clearBrowserData 是通过跨 JNI 调用的 C++ 底层任务，
                    // 在系统剥夺进程生命的时候我们需要强制 Hold 住前台主线程，为内核清空数据争取宝贵的零点几秒。
                    Thread.sleep(500)
                    // ✅ 清除逻辑执行完毕
                } catch (t: Throwable) {
                    XposedBridge.log("❌ 清除过程遭遇崩溃: ${t.stackTraceToString()}")
                }
            }
        })
    }

    /**
     * 在 Edge 设置页面的工具栏添加菜单按钮
     */
    fun addSettingsButtonToToolbar() = afterAttach {
        val targetClass = "org.chromium.chrome.browser.edge_settings.EdgeSettingsActivity"
        val menuItemId = 10001

        val hookMenu = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.thisObject.javaClass.name != targetClass) return
                try {
                    val menu = param.args[0] as Menu
                    if (menu.findItem(menuItemId) == null) {
                        // 插入按钮
                        val item = menu.add(Menu.NONE, menuItemId, Menu.NONE, "EDGEX")
                        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    }
                } catch (e: Exception) {
                    XposedBridge.log(e)
                }
            }
        }

        // Hook Activity 基类以确保捕捉到所有子类的菜单创建过程（即使子类没有重写这些方法）
        val classActivity = Activity::class.java
        XposedHelpers.findAndHookMethod(classActivity, "onCreateOptionsMenu", Menu::class.java, hookMenu)
        XposedHelpers.findAndHookMethod(classActivity, "onPrepareOptionsMenu", Menu::class.java, hookMenu)

        val clazz = XposedHelpers.findClassIfExists(targetClass, classLoader) ?: return@afterAttach
        XposedHelpers.findAndHookMethod(clazz, "onOptionsItemSelected", MenuItem::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val menuItem = param.args[0] as MenuItem
                if (menuItem.itemId == menuItemId) {
                    val activity = param.thisObject as Activity
                    showModuleSettingsDialog(activity)
                    param.result = true
                }
            }
        })
    }

    private fun showModuleSettingsDialog(activity: Activity) {
        Handler(Looper.getMainLooper()).post {
            try {
                // ComponentDialog 自身就是完美的 LifecycleOwner
                val dialog = ComponentDialog(
                    activity,
                    android.R.style.Theme_DeviceDefault_Dialog_NoActionBar
                )

                // 只需要基本的 ContextWrapper 保证资源加载正常
                val moduleContext = object : android.view.ContextThemeWrapper(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar) {
                    override fun getClassLoader(): ClassLoader = MainViewModel::class.java.classLoader!!
                }

                // 实例化你的 ViewModel
                val viewModel = MainViewModelFactory(activity.application).create(MainViewModel::class.java)

                // 使用我们的安全容器包裹 Compose
                dialog.setContentView(ComposeView(moduleContext).apply {
                    // 1. 彻底禁用自动填充，防止 Edge 的 Autofill 服务介入
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS

                    // 2. 彻底隐藏无障碍节点，防止 Edge 的无障碍服务遍历死循环
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    setContent {
                        EdgeXTheme {
                            Scaffold(modifier = androidx.compose.ui.Modifier
                                // 3. 清除该节点及其子节点的所有语义（Accessibility/Autofill 也就看不到它了）
                                .clearAndSetSemantics{}
                                .fillMaxWidth()
                            ) { innerPadding ->
                                MainScreen(viewModel = viewModel, modifier = androidx.compose.ui.Modifier.padding(innerPadding))
                            }
                        }
                    }
                })
                dialog.show()
            } catch (e: Exception) {
                XposedBridge.log(e)
            }
        }
    }

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

    @Serializable
    private data class EdgeHookConfig(
        override val versionCode: Long,
        // 新建标签页方法
        val methodLaunchNtp: String,
        // 新建标签页方法调用该方法
        val methodLaunchUrl: String?,
        // 上面方法的名字
        val launchUrlMethodName: String?,
        // 调用 底部新建标签页按钮.setOnClickListener() 的方法
        val methodThatCallNewTabButtonSetOnClickListener: String,
        // 新建标签页 ActivityTabProvider
        val fieldNameNewTabButtonActivityProvider: String?,
        // 底部新建标签页按钮
        val fieldNewTabButtonView: String,
        // 底部更多按钮长按回调
        val methodOverflowButtonOnLongClick: String,
        // 用于底部更多按钮长按回调中获取 Tab 对象
        val fieldActivityTabProvider: String,
        // 用于底部更多按钮长按回调中的 Tab 加载指定 url
        val methodLoadUrl: String
    ) : HookConfig

    private fun Context.getHookConfigFromDexKit(): EdgeHookConfig? {
        System.loadLibrary("dexkit")
        DexKitBridge.create(classLoader, true).use { bridge ->
            val excludePackageList = listOf(
                "android",
                "androidx",
                "coil",
                "coil3",
                "com",
                "dagger",
                "eightbitlab",
                "io",
                "kotlin",
                "kotlinx",
                "okhttp3",
                "okio",
                "org",
                "retrofit2",
                "ru",
            )
            val usingString = "TabCreator.launchNtp"
            val classTabCreator = bridge.findClass {
                excludePackages(excludePackageList)
                matcher {
                    modifiers = Modifier.PUBLIC or Modifier.ABSTRACT
                    superClass = "java.lang.Object"
                    addUsingString(usingString, StringMatchType.Equals)
                }
            }.singleOrNull() ?: return null

            val methodLaunchNtp = classTabCreator.findMethod {
                matcher {
                    addUsingString(usingString, StringMatchType.Equals)
                }
            }.singleOrNull() ?: return null

            var methodLaunchUrl: MethodData? = null
            var launchUrlMethodName: String? = null
            if (packageManager.getPackageInfo(packageName, 0).longVersionCode >= 365008823) {
                methodLaunchNtp.invokes.findMethod {
                    matcher {
                        this.declaredClass = methodLaunchNtp.paramTypes[0].name
                        paramTypes(Int::class.javaPrimitiveType, String::class.java)
                        returnType = "org.chromium.chrome.browser.tab.Tab"
                    }
                }.singleOrNull()?.let {
                    launchUrlMethodName = it.name
                }
            } else {
                methodLaunchUrl = classTabCreator.findMethod {
                    matcher {
                        modifiers = Modifier.PUBLIC or Modifier.ABSTRACT
                        paramTypes(Int::class.javaPrimitiveType, String::class.java)
                        addCaller(methodLaunchNtp.descriptor)
                    }
                }.singleOrNull()
            }

            val newTabButtonString = "Microsoft.Mobile.BottomBarButton.NewTabButton.Impression"

            val newTabButtonImpressionClass = bridge.findClass {
                excludePackages(excludePackageList)
                matcher {
                    modifiers = Modifier.PUBLIC
                    usingStrings(newTabButtonString)
                }
            }

            val methodThatCallNewTabButtonSetOnClickListener =
                newTabButtonImpressionClass.findMethod {
                    matcher {
                        usingStrings(newTabButtonString)
                    }
                }.singleOrNull() ?: return null

            val fieldNameNewTabButtonActivityProvider =
                if (packageManager.getPackageInfo(packageName, 0).longVersionCode >= 365008823) {
                    newTabButtonImpressionClass.findField {
                        matcher {
                            type = "org.chromium.chrome.browser.ActivityTabProvider"
                        }
                    }.singleOrNull()?.name ?: return null
                } else {
                    null
                }


            val fieldNewTabButtonView =
                methodThatCallNewTabButtonSetOnClickListener.usingFields.singleOrNull {
                    it.usingType.isRead() && it.field.typeName == View::class.java.name
                }?.field ?: return null

            val overflowButtonOnLongClickListenerClass = bridge.findClass {
                excludePackages(excludePackageList)
                matcher {
                    // public static final int SYNTHETIC = 0x00001000;
                    modifiers = Modifier.PUBLIC or Modifier.FINAL or 0x00001000
                    superClass = "java.lang.Object"
                    addUsingString("Microsoft.Mobile.BottomBarButton.OverflowButton.ClickAction")
                    addInterface(View.OnLongClickListener::class.java.name)
                }
            }.singleOrNull() ?: return null

            val methodOverflowButtonOnLongClick =
                overflowButtonOnLongClickListenerClass.findMethod {
                    matcher {
                        name = "onLongClick"
                    }
                }.singleOrNull() ?: return null

            val fieldActivityTabProvider = overflowButtonOnLongClickListenerClass.findField {
                matcher {
                    type = "org.chromium.chrome.browser.ActivityTabProvider"
                }
            }.singleOrNull() ?: return null

            val loadUrlMethodName = bridge.findClass {
                matcher {
                    className("org.chromium.chrome.browser.tab.TabImpl")
                }
            }.findMethod {
                matcher {
                    paramTypes("org.chromium.content_public.browser.LoadUrlParams")
                    usingStrings("Tab.loadUrl")
                }
            }.singleOrNull()?.methodName ?: return null

            val methodLoadUrl = bridge.findClass {
                matcher {
                    className("org.chromium.chrome.browser.tab.Tab")
                }
            }.findMethod {
                matcher {
                    paramTypes("org.chromium.content_public.browser.LoadUrlParams")
                    name = loadUrlMethodName
                }
            }.singleOrNull() ?: return null

            return EdgeHookConfig(
                versionCode = packageManager.getPackageInfo(packageName, 0).longVersionCode,
                methodLaunchNtp = methodLaunchNtp.toDexMethod().serialize(),
                methodLaunchUrl = methodLaunchUrl?.toDexMethod()?.serialize(),
                launchUrlMethodName = launchUrlMethodName,
                fieldNewTabButtonView = fieldNewTabButtonView.toDexField().serialize(),
                methodOverflowButtonOnLongClick = methodOverflowButtonOnLongClick.toDexMethod()
                    .serialize(),
                fieldActivityTabProvider = fieldActivityTabProvider.toDexField().serialize(),
                methodThatCallNewTabButtonSetOnClickListener = methodThatCallNewTabButtonSetOnClickListener.toDexMethod()
                    .serialize(),
                fieldNameNewTabButtonActivityProvider = fieldNameNewTabButtonActivityProvider,
                methodLoadUrl = methodLoadUrl.toDexMethod().serialize()
            )
        }
    }
}
