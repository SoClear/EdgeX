package io.github.soclear.edgex.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.AndroidAppHelper
import android.app.DownloadManager
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
import io.github.soclear.edgex.hook.util.afterAttach
import io.github.soclear.edgex.hook.util.allFields
import io.github.soclear.edgex.hook.util.getHookConfig
import org.luckypray.dexkit.wrap.DexMethod
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.text.DecimalFormat
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt


object Download {

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

        val applicationStatusClass = XposedHelpers.findClassIfExists(
            "org.chromium.base.ApplicationStatus",
            classLoader
        )

        fun getTopActivity(): Activity? {
            val fromRef = topActivityRef?.get()
            if (fromRef != null && !fromRef.isFinishing && !fromRef.isDestroyed) {
                return fromRef
            }
            if (applicationStatusClass != null) {
                val fromAppStatus = try {
                    XposedHelpers.callStaticMethod(applicationStatusClass, "getLastTrackedFocusedActivity") as? Activity
                } catch (_: Throwable) {
                    try {
                        XposedHelpers.getStaticObjectField(applicationStatusClass, "d") as? Activity
                    } catch (_: Throwable) {
                        null
                    }
                }
                if (fromAppStatus != null && !fromAppStatus.isFinishing && !fromAppStatus.isDestroyed) {
                    return fromAppStatus
                }
            }
            return null
        }

        // 已接管的下载 GUID（onDownloadUpdated 会多次回调，用它去重，只在首次接管）
        val handledGuids = java.util.Collections.synchronizedSet(HashSet<String>())
        // 缓存 GURL 取 spec 方法名的键（附加在 GURL Class 上）
        val gurlSpecMethodKey = "EdgeXGurlSpecMethod"

        if (blockOriginalDownloadDialog) {
            val downloadDialogBridge = XposedHelpers.findClassIfExists(
                "org.chromium.chrome.browser.download.DownloadDialogBridge",
                classLoader
            )
            val duplicateBridgeClass = XposedHelpers.findClassIfExists(
                "org.chromium.chrome.browser.download.DuplicateDownloadDialogBridge",
                classLoader
            )
            val messageUiController = XposedHelpers.findClassIfExists(
                "org.chromium.chrome.browser.edge_hub.downloads.EdgeDownloadMessageUiControllerImpl",
                classLoader
            )
            val edgeDownloadManagerHelper = XposedHelpers.findClassIfExists(
                "org.chromium.chrome.browser.edge_hub.downloads.EdgeDownloadManagerHelper",
                classLoader
            )
            val edgeDownloadManagerFeatureBridge = XposedHelpers.findClassIfExists(
                "org.chromium.chrome.browser.download.EdgeDownloadManagerFeatureBridge",
                classLoader
            )
            val offlineItemClass = XposedHelpers.findClassIfExists(
                "org.chromium.components.offline_items_collection.OfflineItem",
                classLoader
            )
            val updateDeltaClass = XposedHelpers.findClassIfExists(
                "org.chromium.components.offline_items_collection.UpdateDelta",
                classLoader
            )
            val windowAndroidClass = XposedHelpers.findClassIfExists(
                "org.chromium.ui.base.WindowAndroid",
                classLoader
            )
            val profileClass = XposedHelpers.findClassIfExists(
                "org.chromium.chrome.browser.profiles.Profile",
                classLoader
            )

            if (messageUiController != null && offlineItemClass != null && updateDeltaClass != null) {
                // Edge 的下载提示是 in-app notification，不是 android.app.Dialog。
                XposedHelpers.findAndHookMethod(
                    messageUiController,
                    "onItemUpdated",
                    offlineItemClass,
                    updateDeltaClass,
                    XC_MethodReplacement.returnConstant(null)
                )
            }

            if (edgeDownloadManagerHelper != null) {
                // 启用新版下载流判断，避免进入旧版 ModalDialog 弹窗分支
                XposedHelpers.findAndHookMethod(
                    edgeDownloadManagerHelper,
                    "isUseNewDownloadDialogFlowEnabled",
                    XC_MethodReplacement.returnConstant(true)
                )
                XposedHelpers.findAndHookMethod(
                    edgeDownloadManagerHelper,
                    "isInAppNotificationEnabled",
                    XC_MethodReplacement.returnConstant(false)
                )
            }

            if (edgeDownloadManagerFeatureBridge != null) {
                XposedHelpers.findAndHookMethod(
                    edgeDownloadManagerFeatureBridge,
                    "isUseNewDownloadDialogFlowEnabled",
                    XC_MethodReplacement.returnConstant(true)
                )
                // 禁用 Edge 原生下载确认弹窗
                XposedHelpers.findAndHookMethod(
                    edgeDownloadManagerFeatureBridge,
                    "shouldConfirmDownload",
                    XC_MethodReplacement.returnConstant(false)
                )
            }

            if (duplicateBridgeClass != null) {
                // DuplicateDownloadDialogBridge.showDialog 在 z2 为 true 时直接回调 native 并返回，不弹窗
                XposedBridge.hookAllMethods(duplicateBridgeClass, "showDialog", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args.size > 6 && param.args[6] is Boolean) {
                            param.args[6] = true
                        }
                    }
                })
            }

            if (downloadDialogBridge != null && windowAndroidClass != null && profileClass != null) {
                var completeDialogMethod: Method? = null
                XposedHelpers.findAndHookMethod(
                    downloadDialogBridge,
                    "showDialog",
                    windowAndroidClass,
                    Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    profileClass,
                    Boolean::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                try {
                                    XposedHelpers.setObjectField(param.thisObject, "c", param.args[0])
                                    XposedHelpers.setObjectField(param.thisObject, "f", param.args[5])
                                } catch (_: Throwable) {}
                                val suggestedPath = (param.args[4] as? String)?.takeIf { it.isNotEmpty() } ?: ""
                                val method = completeDialogMethod
                                    ?: param.thisObject.javaClass.declaredMethods.singleOrNull {
                                        it.returnType == Void.TYPE &&
                                                it.parameterTypes.size == 2 &&
                                                it.parameterTypes[0] == String::class.java &&
                                                it.parameterTypes[1] == Boolean::class.javaPrimitiveType
                                    }?.also {
                                        it.isAccessible = true
                                        completeDialogMethod = it
                                    }
                                method?.invoke(param.thisObject, suggestedPath, false)
                            } catch (t: Throwable) {
                                XposedBridge.log(t)
                            } finally {
                                param.result = null
                            }
                        }
                    }
                )
            }

            var hookConfig = getHookConfig { getHookConfigFromDexKit() }
            if (hookConfig != null && (hookConfig.methodDangerousDownloadConfirm == null || hookConfig.methodDangerousDownloadCondition == null)) {
                // 旧缓存未包含下载相关方法，清除并重新解析生成
                File(filesDir, "EdgeXHookConfig.json").delete()
                hookConfig = getHookConfig { getHookConfigFromDexKit() }
            }

            if (hookConfig != null) {
                hookConfig.methodDangerousDownloadCondition?.let { descriptor ->
                    try {
                        val method = DexMethod(descriptor).getMethodInstance(classLoader)
                        XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(true))
                    } catch (t: Throwable) {
                        XposedBridge.log(t)
                    }
                }

                hookConfig.methodDangerousDownloadConfirm?.let { descriptor ->
                    try {
                        val method = DexMethod(descriptor).getMethodInstance(classLoader)
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    val callback = param.args[2]
                                    if (callback != null) {
                                        XposedHelpers.callMethod(callback, "onResult", true)
                                    }
                                    param.result = null
                                } catch (t: Throwable) {
                                    XposedBridge.log(t)
                                }
                            }
                        })
                    } catch (t: Throwable) {
                        XposedBridge.log(t)
                    }
                }
            }
        }

        val downloadManagerService = XposedHelpers.findClassIfExists(
            "org.chromium.chrome.browser.download.DownloadManagerService",
            classLoader
        ) ?: return@afterAttach

        // Edge 150+ 起，下载完全由 Chromium 原生引擎处理：DownloadManagerService.onDownloadItemCreated
        // 已成死代码，cookie/UA 也不再传到 Java 层。实测真正会触发的是
        // DownloadController.onDownloadUpdated(DownloadInfo)（下载进度回调）。
        val downloadController = XposedHelpers.findClassIfExists(
            "org.chromium.chrome.browser.download.DownloadController",
            classLoader
        ) ?: return@afterAttach

        XposedHelpers.findAndHookMethod(
            downloadController,
            "onDownloadUpdated",
            "org.chromium.chrome.browser.download.DownloadInfo",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val downloadInfo = param.args[0] ?: return

                        // GUID（DownloadInfo.l），用于去重与取消 Edge 原生下载
                        val guid = XposedHelpers.getObjectField(downloadInfo, "l") as? String
                            ?: return
                        // onDownloadUpdated 会随进度多次回调，只在首次接管
                        if (!handledGuids.add(guid)) return

                        val mimeType = XposedHelpers.getObjectField(downloadInfo, "c") as? String
                        // 排除插件
                        if (mimeType == "application/x-chrome-extension") {
                            return
                        }

                        // URL（DownloadInfo.a 是 GURL），取字符串（方法名混淆，反射解析）
                        val url = gurlToString(XposedHelpers.getObjectField(downloadInfo, "a"))
                        if (url.isNullOrEmpty()) return

                        // referrer（DownloadInfo.h 是 GURL）
                        val referrer = gurlToString(XposedHelpers.getObjectField(downloadInfo, "h"))
                        // cookie / UA 在新版 Edge 原生下载路径已不再传到 Java 层，恒为 null
                        val cookie = XposedHelpers.getObjectField(downloadInfo, "d") as? String
                        val userAgent = XposedHelpers.getObjectField(downloadInfo, "b") as? String
                        // 文件名：优先用 DownloadInfo.e，否则从 url 推断
                        val fileName = (XposedHelpers.getObjectField(downloadInfo, "e") as? String)
                            ?.takeIf { it.isNotEmpty() }
                            ?: URLUtil.guessFileName(url, null, mimeType)
                        // 真实文件大小（DownloadInfo.k），拿不到则为 0/-1
                        val totalBytes = try {
                            XposedHelpers.getLongField(downloadInfo, "k")
                        } catch (_: Throwable) {
                            0L
                        }
                        // OtrProfileId（DownloadInfo.p），取消下载时需要
                        val otrProfileId = XposedHelpers.getObjectField(downloadInfo, "p")

                        val activity = getTopActivity()
                        val isValidActivity =
                            activity != null && !activity.isFinishing && !activity.isDestroyed
                        val context: Context =
                            (if (isValidActivity) activity else AndroidAppHelper.currentApplication())
                                ?: return

                        // 只有在我们确实要接管时才取消 Edge 的原生下载，否则放行，避免下载丢失
                        if (setDefaultDownloader) {
                            cancelEdgeDownload(guid, otrProfileId)
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
                        } else if (isValidActivity) {
                            cancelEdgeDownload(guid, otrProfileId)
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
                        } else {
                            // 没有可用 Activity 弹窗，又未设默认下载器：放行 Edge 原生下载，
                            // 并把 GUID 从已处理集合移除，等下次回调再尝试接管
                            handledGuids.remove(guid)
                        }
                    } catch (t: Throwable) {
                        XposedBridge.log(t)
                    }
                }

                /**
                 * 取消 Edge 已经开始的原生下载。用 DownloadManagerService.removeDownload
                 * （按 GUID 删除，走 native）。
                 */
                private fun cancelEdgeDownload(
                    guid: String,
                    otrProfileId: Any?
                ) {
                    val dms = try {
                        XposedHelpers.callStaticMethod(downloadManagerService, "a")
                    } catch (t: Throwable) {
                        XposedBridge.log(t)
                        return
                    }
                    try {
                        XposedHelpers.callMethod(dms, "removeDownload", guid, otrProfileId, false)
                    } catch (t: Throwable) {
                        XposedBridge.log(t)
                    }
                }

                /**
                 * 将 GURL 对象转成字符串。GURL 取 spec 的方法在混淆下每个版本可能不同，
                 * 这里通过“无参、返回 String、且返回值形如完整 URL”的特征反射定位并缓存。
                 */
                private fun gurlToString(gurl: Any?): String? {
                    if (gurl == null) return null
                    val clazz = gurl.javaClass
                    val cachedName = XposedHelpers.getAdditionalStaticField(
                        clazz,
                        gurlSpecMethodKey
                    ) as? String
                    if (cachedName != null) {
                        return XposedHelpers.callMethod(gurl, cachedName) as? String
                    }
                    for (method in clazz.declaredMethods) {
                        if (method.parameterTypes.isNotEmpty()) continue
                        if (method.returnType != String::class.java) continue
                        try {
                            method.isAccessible = true
                            val value = method.invoke(gurl) as? String ?: continue
                            if (value.contains("://") || value.startsWith("blob:") ||
                                value.startsWith("data:")
                            ) {
                                XposedHelpers.setAdditionalStaticField(
                                    clazz,
                                    gurlSpecMethodKey,
                                    method.name
                                )
                                return value
                            }
                        } catch (_: Throwable) {
                        }
                    }
                    return null
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
                        if (activity.isFinishing || activity.isDestroyed) return@post
                        try {
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
                                .show()
                        } catch (t: Throwable) {
                            XposedBridge.log(t)
                        }
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
                    val currentUrl = gurlToString(gurl)
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
}
