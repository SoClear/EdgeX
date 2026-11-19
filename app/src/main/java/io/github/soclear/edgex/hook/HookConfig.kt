package io.github.soclear.edgex.hook

import android.content.Context
import android.view.View
import io.github.soclear.edgex.hook.util.HookConfig
import kotlinx.serialization.Serializable
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier


@Serializable
internal data class EdgeHookConfig(
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

internal fun Context.getHookConfigFromDexKit(): EdgeHookConfig? {
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
