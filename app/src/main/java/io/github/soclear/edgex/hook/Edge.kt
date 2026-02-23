package io.github.soclear.edgex.hook

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsetsController
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import kotlinx.serialization.Serializable
import io.github.soclear.edgex.hook.util.HookConfig
import io.github.soclear.edgex.hook.util.afterAttach
import io.github.soclear.edgex.hook.util.getHookConfig
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier


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
    fun setNewTabPageUrl(url: String) {
        afterAttach {
            val hookConfig = getHookConfig { getHookConfigFromDexKit() }
            if (hookConfig != null) {
                val launchNtpMethod = try {
                    DexMethod(hookConfig.methodLaunchNtp).getMethodInstance(classLoader)
                } catch (_: NoSuchMethodException) {
                    return@afterAttach
                }

                if (hookConfig.launchUrlMethodName != null) {
                    XposedBridge.hookMethod(
                        launchNtpMethod, object : XC_MethodReplacement() {
                            override fun replaceHookedMethod(param: MethodHookParam): Any? {
                                XposedHelpers.callMethod(
                                    param.args[0],
                                    hookConfig.launchUrlMethodName,
                                    param.args[2],
                                    url
                                )
                                return null
                            }
                        }
                    )

                }

                if (hookConfig.methodLaunchUrl != null) {
                    val methodLaunchUrl =
                        DexMethod(hookConfig.methodLaunchUrl).getMethodInstance(classLoader)

                    XposedBridge.hookMethod(
                        launchNtpMethod, object : XC_MethodReplacement() {
                            override fun replaceHookedMethod(param: MethodHookParam): Any? {
                                methodLaunchUrl(param.thisObject, param.args[0], url)
                                return null
                            }
                        }
                    )
                }
            }
        }
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
