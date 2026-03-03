package io.github.soclear.edgex.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.app.Dialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Browser
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsetsController
import android.webkit.URLUtil
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.soclear.edgex.R
import io.github.soclear.edgex.data.DownloaderType
import io.github.soclear.edgex.hook.util.HookConfig
import io.github.soclear.edgex.hook.util.afterAttach
import io.github.soclear.edgex.hook.util.allFields
import io.github.soclear.edgex.hook.util.getHookConfig
import kotlinx.serialization.Serializable
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod
import java.io.File
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
     * 移除底部 padding
     */
    fun removeBottomPadding() {
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
                        param.args[0] = 0
                        param.args[1] = 0
                        param.args[2] = 0
                        param.args[3] = 0
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

    /**
     * 隐藏状态栏（沉浸）
     */
    fun hideStatusBar() {
        XposedHelpers.findAndHookMethod(
            Activity::class.java,
            "onCreate",
            Bundle::class.java,
            object : XC_MethodHook() {
                @Suppress("DEPRECATION")
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    val flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        .or(View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
                        .or(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
                        .or(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
                        .or(View.SYSTEM_UI_FLAG_FULLSCREEN)
                        .or(View.SYSTEM_UI_FLAG_IMMERSIVE)
                    activity.window.decorView.systemUiVisibility = flags

                    val decorView = activity.window.decorView
                    decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                        if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                            // 系统栏显示时，延迟2秒 重新隐藏
                            decorView.postDelayed({
                                decorView.systemUiVisibility = flags
                            }, 2000)
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        }
                    }
                }
            }
        )
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
            "onCreate",
            Bundle::class.java,
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
                        if (activity.isFinishing) return

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
                                    activity
                                )
                            } else {
                                thirdPartyDownload(
                                    url,
                                    mimeType,
                                    cookie,
                                    userAgent,
                                    referrer,
                                    activity,
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
                    activity: Activity
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
                        activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    downloadManager.enqueue(request)
                }

                private fun thirdPartyDownload(
                    url: String,
                    mimeType: String?,
                    cookie: String?,
                    userAgent: String?,
                    referrer: String?,
                    activity: Activity,
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
                            activity.startActivity(chooser)
                        } else {
                            // 指定了包名
                            intent.setPackage(targetPackageName)
                            val pm = activity.packageManager

                            // 局部函数：寻找目标并用 ApplicationContext 强制启动
                            val tryForceLaunch = { testIntent: Intent ->
                                val resolveInfo = pm.resolveActivity(testIntent, 0)
                                if (resolveInfo != null) {
                                    // 强制转为显式 Intent，获取迅雷内部具体处理下载的 Activity 类名
                                    testIntent.component = ComponentName(
                                        resolveInfo.activityInfo.packageName,
                                        resolveInfo.activityInfo.name
                                    )

                                    // 使用 applicationContext 绕过 Edge 浏览器对 startActivity 的拦截
                                    activity.applicationContext.startActivity(testIntent)
                                    true
                                } else {
                                    false
                                }
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
                                    activity,
                                    getString(R.string.download_target_app_failed),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(
                            activity,
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

    fun addAssetPath(modulePath: String) {
        XposedHelpers.findAndHookMethod(
            Application::class.java,
            "attach",
            Context::class.java,
            object : XC_MethodHook() {
                @SuppressLint("ObsoleteSdkInt")
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val context = param.args[0] as Context
                        val loader = ResourcesLoader()
                        val moduleFile = File(modulePath)
                        val parcelFileDescriptor = ParcelFileDescriptor.open(
                            moduleFile,
                            ParcelFileDescriptor.MODE_READ_ONLY
                        )
                        val provider = ResourcesProvider.loadFromApk(parcelFileDescriptor)
                        loader.addProvider(provider)
                        context.resources.addLoaders(loader)
                    } else {
                        val context = param.args[0] as Context
                        XposedHelpers.callMethod(
                            context.assets,
                            "addAssetPath",
                            modulePath
                        )
                    }
                }
            }
        )
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
