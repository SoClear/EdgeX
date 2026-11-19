package io.github.soclear.edgex.hook

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.soclear.edgex.hook.util.HookConfig
import io.github.soclear.edgex.hook.util.getHookConfig
import kotlinx.serialization.Serializable
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.wrap.DexMethod
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

private const val DOWNLOAD = "org.chromium.chrome.browser.download"
private const val DOWNLOAD_HUB = "org.chromium.chrome.browser.edge_hub.downloads"
private const val DANGEROUS_BRIDGE = "$DOWNLOAD.DangerousDownloadDialogBridge"
private const val MESSAGE_UI = "$DOWNLOAD_HUB.EdgeDownloadMessageUiControllerImpl"
private const val OFFLINE_ITEMS = "org.chromium.components.offline_items_collection"
private const val CALLBACK = "org.chromium.base.Callback"

internal fun Context.hideNativeDownloadDialog(
    takeOver: (Activity, String, Any, Boolean, () -> Unit) -> Boolean
) {
    fun edgeClass(name: String) = XposedHelpers.findClassIfExists(name, classLoader)

    fun Class<*>?.replaceFlag(name: String, value: Boolean) {
        val method = this?.let { XposedHelpers.findMethodExactIfExists(it, name) } ?: return
        if (method.returnType == Boolean::class.javaPrimitiveType) {
            XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(value))
        }
    }

    val messageUi = edgeClass(MESSAGE_UI)
    val offlineItem = edgeClass("$OFFLINE_ITEMS.OfflineItem")
    val updateDelta = edgeClass("$OFFLINE_ITEMS.UpdateDelta")
    if (messageUi != null && offlineItem != null && updateDelta != null) {
        XposedHelpers.findMethodExactIfExists(messageUi, "onItemUpdated", offlineItem, updateDelta)
            ?.let { XposedBridge.hookMethod(it, XC_MethodReplacement.returnConstant(null)) }
    }

    val managerHelper = edgeClass("$DOWNLOAD_HUB.EdgeDownloadManagerHelper")
    managerHelper.replaceFlag("isUseNewDownloadDialogFlowEnabled", true)
    managerHelper.replaceFlag("isInAppNotificationEnabled", false)

    val featureBridge = edgeClass("$DOWNLOAD.EdgeDownloadManagerFeatureBridge")
    featureBridge.replaceFlag("isUseNewDownloadDialogFlowEnabled", true)
    featureBridge.replaceFlag("shouldConfirmDownload", false)

    edgeClass("$DOWNLOAD.DuplicateDownloadDialogBridge")?.let {
        XposedBridge.hookAllMethods(it, "showDialog", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // 新版流程中，这个参数为 true 时，Edge 自己完成回调而不显示重复下载提示。
                if (param.args.getOrNull(6) is Boolean) param.args[6] = true
            }
        })
    }

    hideDownloadLocationDialog()
    interceptPendingDownload(takeOver)
}

private fun Context.hideDownloadLocationDialog() {
    val bridge = XposedHelpers.findClassIfExists("$DOWNLOAD.DownloadDialogBridge", classLoader)
        ?: return
    val window = XposedHelpers.findClassIfExists("org.chromium.ui.base.WindowAndroid", classLoader)
        ?: return
    val profile = XposedHelpers.findClassIfExists("org.chromium.chrome.browser.profiles.Profile", classLoader)
        ?: return
    val show = XposedHelpers.findMethodExactIfExists(
        bridge, "showDialog", window, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType, String::class.java, profile, Boolean::class.javaPrimitiveType
    ) ?: return
    val complete = bridge.declaredMethods.singleOrNull {
        it.returnType == Void.TYPE &&
            it.parameterTypes.toList() == listOf(String::class.java, Boolean::class.javaPrimitiveType)
    }?.apply { isAccessible = true } ?: return
    val windowField = bridge.declaredFields.singleOrNull { it.type == window }
        ?.apply { isAccessible = true } ?: return
    val profileField = bridge.declaredFields.singleOrNull { it.type == profile }
        ?.apply { isAccessible = true } ?: return

    XposedBridge.hookMethod(show, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val suggestedPath = (param.args[4] as? String)?.takeIf { it.isNotEmpty() } ?: return
            windowField.set(param.thisObject, param.args[0])
            profileField.set(param.thisObject, param.args[5])
            complete.invoke(param.thisObject, suggestedPath, false)
            param.result = null
        }
    })
}

private fun Context.interceptPendingDownload(
    takeOver: (Activity, String, Any, Boolean, () -> Unit) -> Boolean
) {
    // 查询接口在 base.apk，presenter 和 provider 工厂在 chrome split。
    val aggregator = XposedHelpers.findClassIfExists("$OFFLINE_ITEMS.OfflineContentAggregatorBridge", classLoader)
        ?: return
    val providerType = aggregator.interfaces.singleOrNull()?.name ?: return
    val getItem = aggregator.declaredMethods.singleOrNull {
        it.returnType == Void.TYPE && it.parameterTypes.size == 2 && it.parameterTypes[1].name == CALLBACK
    } ?: return
    val config = getHookConfig(File(filesDir, "EdgeXDownloadDialogHookConfig.json")) {
        resolveDownloadDialog(providerType)
    } ?: return
    fun getMethod(descriptor: String) = DexMethod(descriptor).getMethodInstance(classLoader)
    val method = getMethod(config.presenter)
    val lookup = PendingDownloads(getMethod(config.provider), getItem, getMethod(config.toDownloadInfo))
    val handler = Handler(Looper.getMainLooper())
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val presenter = param.thisObject ?: return
            val callback = param.args[5] ?: return
            val guid = pendingGuid(callback, presenter) ?: return
            val activity = param.args[0] as Activity
            val dangerous = param.args[6] as Boolean
            val args = param.args.clone()

            // 审批前按 ID 取链接。接管成功后取消原下载，不让 Edge 先保存文件。
            var waiting = true
            fun finish(info: Any?) {
                if (!waiting) return
                waiting = false
                if (pendingGuid(callback, presenter) != guid) return
                val taken = info != null && takeOver(activity, guid, info, dangerous) {
                    XposedHelpers.callMethod(callback, "onResult", 2)
                }
                if (!taken) XposedBridge.invokeOriginalMethod(method, presenter, args)
            }

            lookup.find(guid, ::finish)
            handler.postDelayed({ finish(null) }, 3000)
            param.result = null
        }
    })
}

private fun pendingGuid(callback: Any, presenter: Any): String? {
    val bridge = callback.javaClass.declaredFields.singleOrNull { it.type.name == DANGEROUS_BRIDGE }
        ?.apply { isAccessible = true }?.get(callback) ?: return null
    // 桥接类用 GUID 持有正在展示的 presenter，直接按实例匹配，不靠混淆字段名。
    return bridge.javaClass.declaredFields
        .filter { Map::class.java.isAssignableFrom(it.type) }
        .asSequence()
        .mapNotNull { it.apply { isAccessible = true }.get(bridge) as? Map<*, *> }
        .flatMap { it.entries.asSequence() }
        .singleOrNull { it.value === presenter }?.key as? String
}

private class PendingDownloads(
    private val provider: Method,
    private val getItem: Method,
    private val toDownloadInfo: Method
) {
    private val idConstructor = getItem.parameterTypes[0]
        .getDeclaredConstructor(String::class.java, String::class.java)
    private val callbackType = getItem.parameterTypes[1]

    fun find(guid: String, result: (Any?) -> Unit) {
        val instance = provider.invoke(null)
        fun findIn(namespace: String, found: (Any?) -> Unit) {
            val callback = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { _, method, args ->
                if (method.name == "onResult") found(args?.get(0))
                null
            }
            getItem.invoke(instance, idConstructor.newInstance(namespace, guid), callback)
        }

        fun finish(item: Any?) = result(item?.let { toDownloadInfo.invoke(null, it, null) })
        findIn("LEGACY_DOWNLOAD") { item ->
            if (item != null) finish(item) else findIn("LEGACY_DOWNLOAD_INCOGNITO", ::finish)
        }
    }
}

private fun Context.resolveDownloadDialog(providerType: String): DownloadDialogHookConfig? {
    System.loadLibrary("dexkit")
    DexKitBridge.create(classLoader, true).use { dex ->
        val presenter = dex.getClassData(DANGEROUS_BRIDGE)?.methods
            ?.flatMap { it.invokes }
            ?.distinctBy { it.descriptor }
            ?.singleOrNull {
                val types = it.paramTypeNames
                it.returnTypeName == "void" && types.size == 7 &&
                    types[0] == "android.app.Activity" &&
                    types.drop(2) == listOf("java.lang.String", "long", "java.lang.String", CALLBACK, "boolean")
            } ?: return null
        val provider = dex.getClassData(MESSAGE_UI)?.methods
            ?.flatMap { it.invokes }
            ?.distinctBy { it.descriptor }
            ?.singleOrNull {
                Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == providerType
            } ?: return null
        val toDownloadInfo = dex.getClassData("$DOWNLOAD.DownloadInfo")?.methods?.singleOrNull {
            Modifier.isStatic(it.modifiers) && it.returnTypeName == "$DOWNLOAD.DownloadInfo" &&
                it.paramTypeNames == listOf("$OFFLINE_ITEMS.OfflineItem", "$OFFLINE_ITEMS.OfflineItemVisuals")
        } ?: return null
        return DownloadDialogHookConfig(
            packageManager.getPackageInfo(packageName, 0).longVersionCode,
            presenter.descriptor, provider.descriptor, toDownloadInfo.descriptor
        )
    }
}

@Serializable
private data class DownloadDialogHookConfig(
    override val versionCode: Long,
    val presenter: String,
    val provider: String,
    val toDownloadInfo: String
) : HookConfig
