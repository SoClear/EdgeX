package io.github.soclear.edgex.hook

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.clearAndSetSemantics
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.soclear.edgex.MainViewModel
import io.github.soclear.edgex.MainViewModelFactory
import io.github.soclear.edgex.hook.util.afterAttach
import io.github.soclear.edgex.ui.MainScreen
import io.github.soclear.edgex.ui.theme.EdgeXTheme

object SettingsButton {

    /**
     * 在 Edge 设置页面的工具栏添加菜单按钮
     */
    fun addSettingsButtonToToolbar() = afterAttach {
        val targetClass = "org.chromium.chrome.browser.edge_settings.EdgeSettingsActivity"
        val menuItemId = 10001
        val classActivity = Activity::class.java

        val hookMenu = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (activity.javaClass.name != targetClass) return
                try {
                    val menu = param.args[0] as Menu
                    if (menu.findItem(menuItemId) == null) {
                        val item = menu.add(Menu.NONE, menuItemId, Menu.NONE, "EDGEX")
                        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                        item.setOnMenuItemClickListener {
                            showModuleSettingsDialog(activity)
                            true
                        }
                    }
                } catch (e: Exception) {
                    XposedBridge.log(e)
                }
            }
        }

        XposedHelpers.findAndHookMethod(classActivity, "onCreateOptionsMenu", Menu::class.java, hookMenu)
        XposedHelpers.findAndHookMethod(classActivity, "onPrepareOptionsMenu", Menu::class.java, hookMenu)

        XposedHelpers.findAndHookMethod(classActivity, "onOptionsItemSelected", MenuItem::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (activity.javaClass.name != targetClass) return
                val menuItem = param.args[0] as MenuItem
                if (menuItem.itemId == menuItemId) {
                    showModuleSettingsDialog(activity)
                    param.result = true
                }
            }
        })
    }

    private fun showModuleSettingsDialog(activity: Activity) {
        XposedBridge.log("[EdgeX][SettingsButton] 点击了 EDGEX 菜单，准备展示设置弹窗")
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
                XposedBridge.log("[EdgeX][SettingsButton] 设置弹窗已成功弹出")
            } catch (e: Exception) {
                XposedBridge.log("[EdgeX][SettingsButton] 展示设置弹窗失败: ${e.message}")
                XposedBridge.log(e)
            }
        }
    }
}
