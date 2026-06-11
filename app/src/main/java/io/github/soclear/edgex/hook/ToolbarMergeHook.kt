import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

// 伪代码：根据 EdgeX 项目实际的 Prefs 工具类读取
val prefs = ... // 获取模块的配置
if (!prefs.getBoolean("merge_toolbar_navigation", false)) {
    return // 如果用户没开这个开关，就不生效
}

fun hookEdgeToolbar(lpparam: LoadPackageParam) {
    // 1. 锁定顶部的地址栏类
    val toolbarClass = "org.chromium.chrome.browser.toolbar.top.EdgeToolbarPhone"

    XposedHelpers.findAndHookMethod(
        toolbarClass,
        lpparam.classLoader,
        "onFinishInflate", // 当地址栏布局加载渲染完成时
        object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val toolbar = param.thisObject as ViewGroup
                val context = toolbar.context
                val density = context.resources.displayMetrics.density

                try {
                    // 2. 寻找地址栏内部的输入框容器（通常是 toolbar 里面的第一个线性或相对布局）
                    // 如果不确定，可以直接将按钮加在 toolbar 的最左侧
                    if (toolbar is LinearLayout) {
                        toolbar.orientation = LinearLayout.HORIZONTAL
                    }

                    // 3. 动态创建我们自己的后退按钮
                    val myBackBtn = ImageView(context).apply {
                        // 使用 Edge 自带的返回图标资源（利用你抓到的 id 间接获取 icon，或者使用系统自带的）
                        setImageResource(android.R.drawable.ic_media_previous) // 临时替代，可改为 Edge 的图标
                        contentDescription = "返回"
                        setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                        
                        // 点击事件：让当前的 Activity 执行后退
                        setOnClickListener {
                            val activity = context as? android.app.Activity
                            activity?.onBackPressed() 
                            // 或者通过反射调用浏览器的 tab.goBack()，但在 Activity 级别触发 onBackPressed 最安全
                        }
                    }

                    // 4. 动态创建我们自己的前进按钮
                    val myForwardBtn = ImageView(context).apply {
                        setImageResource(android.R.drawable.ic_media_next) // 临时替代
                        contentDescription = "前进"
                        setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                        
                        setOnClickListener {
                            // 前进逻辑通常需要获取当前 Tab，这里可以通过反射调用 Edge 的页面控制
                            try {
                                val activity = context as? android.app.Activity
                                // 尝试寻找 Edge 的当前标签页并执行 forward
                                XposedHelpers.callMethod(activity, "goForward") 
                            } catch (e: Throwable) {
                                // 如果普通方法不行，可以通过 Hook 内部的 ChromeTab 类来驱动
                            }
                        }
                    }

                    // 5. 设置按钮的布局参数 (36dp 宽高)
                    val btnSize = (36 * density).toInt()
                    val lp = LinearLayout.LayoutParams(btnSize, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                    myBackBtn.layoutParams = lp
                    myForwardBtn.layoutParams = lp

                    // 6. 将新按钮插入到地址栏的最左边
                    toolbar.addView(myBackBtn, 0)    // 位置 0：最左边后退
                    toolbar.addView(myForwardBtn, 1) // 位置 1：紧接着前进
                    
                    toolbar.requestLayout()
                    toolbar.invalidate()

                } catch (e: Throwable) {
                    XposedBridge.log("EdgeX 融合地址栏失败: ${e.message}")
                }
            }
        }
    )
}
