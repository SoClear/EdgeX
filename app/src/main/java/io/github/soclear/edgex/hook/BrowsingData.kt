package io.github.soclear.edgex.hook

import android.app.Activity
import android.app.Service
import android.content.Intent
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers


object BrowsingData {

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
}
