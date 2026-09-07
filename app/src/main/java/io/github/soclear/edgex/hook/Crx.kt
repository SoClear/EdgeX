package io.github.soclear.edgex.hook

import android.app.Activity
import android.content.Intent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.soclear.edgex.data.Preference
import io.github.soclear.edgex.hook.util.afterAttach
import kotlinx.serialization.json.Json
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier


object Crx {

    private const val CRX_INSTALL_ACTION =
        "com.microsoft.edge.extensions.ACTION_INSTALL_EXTENSION_FOR_DEV_MODE"
    private const val CRX_INSTALL_EXTRA = "com.microsoft.edge.extensions.EXTENSION_CRX"
    private const val CRX_COMPATIBILITY_HANDLED_INTENT_FIELD = "EdgeXCrxCompatibilityHandledIntent"
    private const val CRX_INTENT_METHOD_FIELD = "EdgeXCrxIntentMethod"
    private const val CRX_INSTALL_METHOD_FIELD = "EdgeXCrxInstallMethod"

    private fun logCrx(message: String, throwable: Throwable? = null) {
        XposedBridge.log("[EdgeX][CRX] $message")
        throwable?.let(XposedBridge::log)
    }

    fun crxInstallCompatibility() = afterAttach {
        val appClassLoader = classLoader
        val chromeTabbedActivityClass = XposedHelpers.findClassIfExists(
            "org.chromium.chrome.browser.ChromeTabbedActivity",
            appClassLoader
        ) ?: run {
            logCrx("ChromeTabbedActivity not found")
            return@afterAttach
        }

        val intentHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                val intent = param.args.firstOrNull() as? Intent ?: return
                if (handleCrxInstallCompatibility(activity, intent, appClassLoader)) {
                    param.result = null
                }
            }
        }

        val nativeIntentMethod = findCrxIntentMethod(chromeTabbedActivityClass, appClassLoader)

        var nativeIntentHooked = false
        if (nativeIntentMethod != null) {
            try {
                XposedBridge.hookMethod(nativeIntentMethod, intentHook)
                nativeIntentHooked = true
                logCrx("Hooked intent method: ${nativeIntentMethod.name}")
            } catch (t: Throwable) {
                logCrx("Failed to hook intent method", t)
            }
        } else {
            logCrx("No intent method found")
        }

        if (!nativeIntentHooked) {
            try {
                XposedHelpers.findAndHookMethod(
                    chromeTabbedActivityClass,
                    "onMAMNewIntent",
                    Intent::class.java,
                    intentHook
                )
                nativeIntentHooked = true
                logCrx("Hooked onMAMNewIntent")
            } catch (t: Throwable) {
                logCrx("Failed to hook onMAMNewIntent", t)
            }
        }

        if (!nativeIntentHooked) {
            try {
                XposedHelpers.findAndHookMethod(
                    chromeTabbedActivityClass,
                    "onMAMResume",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val activity = param.thisObject as? Activity ?: return
                            handleCrxInstallCompatibility(activity, activity.intent, appClassLoader)
                        }
                    }
                )
            } catch (t: Throwable) {
                logCrx("Failed to hook onMAMResume", t)
            }
        }
    }

    private fun findCrxIntentMethod(
        chromeTabbedActivityClass: Class<*>,
        classLoader: ClassLoader
    ): Method? {
        (XposedHelpers.getAdditionalStaticField(
            chromeTabbedActivityClass,
            CRX_INTENT_METHOD_FIELD
        ) as? Method)?.let {
            return it
        }

        val directCandidates = chromeTabbedActivityClass.declaredMethods.filter {
            it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(Intent::class.java)) &&
                it.name != "onMAMNewIntent"
        }
        val directMethod = directCandidates.singleOrNull()
        val intentMethod = directMethod ?: try {
            System.loadLibrary("dexkit")
            DexKitBridge.create(classLoader, true).use { bridge ->
                val entryMethods = bridge.findMethod {
                    matcher {
                        usingStrings(CRX_INSTALL_ACTION)
                    }
                }.ifEmpty {
                    bridge.findMethod {
                        matcher {
                            usingStrings(CRX_INSTALL_EXTRA)
                        }
                    }
                }
                val candidates = entryMethods.filter {
                    it.declaredClassName == chromeTabbedActivityClass.name &&
                        it.returnTypeName == Void.TYPE.name &&
                        it.paramTypeNames == listOf(Intent::class.java.name) &&
                        it.methodName != "onMAMNewIntent"
                }
                candidates.singleOrNull()?.getMethodInstance(classLoader)
            }
        } catch (t: Throwable) {
            logCrx("Failed to resolve CRX intent method", t)
            null
        }

        return intentMethod?.also {
            it.isAccessible = true
            XposedHelpers.setAdditionalStaticField(
                chromeTabbedActivityClass,
                CRX_INTENT_METHOD_FIELD,
                it
            )
        }
    }

    // 按调用关系定位混淆方法，避免依赖方法名和声明顺序。
    private fun findCrxInstallMethod(classLoader: ClassLoader): Method? {
        val nativeBridgeClass = XposedHelpers.findClassIfExists("J.N", classLoader)
            ?: run {
                logCrx("Native class J.N not found")
                return null
            }

        (XposedHelpers.getAdditionalStaticField(
            nativeBridgeClass,
            CRX_INSTALL_METHOD_FIELD
        ) as? Method)?.let {
            return it
        }

        val installMethod = try {
            System.loadLibrary("dexkit")
            DexKitBridge.create(classLoader, true).use { bridge ->
                val entryMethods = bridge.findMethod {
                    matcher {
                        usingStrings(CRX_INSTALL_ACTION)
                    }
                }.ifEmpty {
                    bridge.findMethod {
                        matcher {
                            usingStrings(CRX_INSTALL_EXTRA)
                        }
                    }
                }
                val candidates = entryMethods
                    .flatMap { entryMethod ->
                        entryMethod.invokes.findMethod {
                            matcher {
                                declaredClass = "J.N"
                                modifiers = Modifier.STATIC
                                returnType = "void"
                                paramTypes("int", "java.lang.Object")
                            }
                        }
                    }
                    .distinctBy { it.descriptor }
                candidates.singleOrNull()?.getMethodInstance(classLoader)
            }
        } catch (t: Throwable) {
            logCrx("Failed to resolve CRX install method", t)
            null
        } ?: nativeBridgeClass.declaredMethods.singleOrNull {
            Modifier.isStatic(it.modifiers) &&
                it.returnType == Void.TYPE &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Object::class.java
        }

        return installMethod?.also {
            it.isAccessible = true
            XposedHelpers.setAdditionalStaticField(
                nativeBridgeClass,
                CRX_INSTALL_METHOD_FIELD,
                it
            )
        } ?: run {
            logCrx("Unique CRX install method not found")
            null
        }
    }

    private fun isCrxInstallCompatibilityEnabled(activity: Activity): Boolean = try {
        val dataStoreFile = File(
            activity.applicationInfo.dataDir,
            "files/datastore/${Preference.FILE_NAME}"
        )
        Json.decodeFromString<Preference>(dataStoreFile.readText()).crxInstallCompatibility
    } catch (_: Throwable) {
        false
    }

    private fun handleCrxInstallCompatibility(
        activity: Activity,
        intent: Intent?,
        classLoader: ClassLoader
    ): Boolean {
        if (intent?.action != CRX_INSTALL_ACTION) return false

        val crxPath = intent.getStringExtra(CRX_INSTALL_EXTRA)
            ?.takeIf { it.isNotBlank() }
            ?: return false
        val handledIntent = XposedHelpers.getAdditionalInstanceField(
            activity,
            CRX_COMPATIBILITY_HANDLED_INTENT_FIELD
        ) as? Intent
        if (handledIntent === intent) return true
        if (!isCrxInstallCompatibilityEnabled(activity)) return false

        val profileManagerClass = XposedHelpers.findClassIfExists(
            "org.chromium.chrome.browser.profiles.ProfileManager",
            classLoader
        )
        val profileReady = try {
            profileManagerClass == null || XposedHelpers.getStaticBooleanField(profileManagerClass, "b")
        } catch (_: Throwable) {
            true
        }
        if (!profileReady) return false

        val installMethod = findCrxInstallMethod(classLoader) ?: return false

        val stateControllerClass = XposedHelpers.findClassIfExists(
            "com.microsoft.edge.extensions.GlobalExtensionStateController",
            classLoader
        )
        try {
            stateControllerClass?.declaredMethods?.singleOrNull {
                Modifier.isStatic(it.modifiers) &&
                    it.returnType == Void.TYPE &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType
            }?.apply {
                isAccessible = true
                invoke(null, true)
            }
        } catch (t: Throwable) {
            logCrx("Failed to enable extension components", t)
        }

        try {
            installMethod.isAccessible = true
            installMethod.invoke(null, 1, crxPath)
            XposedHelpers.setAdditionalInstanceField(
                activity,
                CRX_COMPATIBILITY_HANDLED_INTENT_FIELD,
                intent
            )
            logCrx("Dispatched CRX install request: $crxPath")
            return true
        } catch (t: Throwable) {
            logCrx("Failed to dispatch CRX install request", t)
            return false
        }
    }
}
