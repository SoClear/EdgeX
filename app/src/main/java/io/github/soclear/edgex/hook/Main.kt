package io.github.soclear.edgex.hook

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.soclear.edgex.data.Preference
import io.github.soclear.edgex.hook.util.ModernXposed
import io.github.soclear.edgex.hook.util.XposedBridge
import io.github.soclear.edgex.hook.util.addAssetPath
import kotlinx.serialization.json.Json
import java.io.File

class Main : XposedModule() {
    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != "com.microsoft.emmx" &&
            param.packageName != "com.microsoft.emmx.beta" &&
            param.packageName != "com.microsoft.emmx.canary" &&
            param.packageName != "com.microsoft.emmx.dev"
        ) {
            return
        }

        ModernXposed.attach(this)
        fun install(name: String, action: () -> Unit) {
            try {
                action()
                XposedBridge.log("Hook installed: $name")
            } catch (t: Throwable) {
                XposedBridge.log(IllegalStateException("Failed to install hook: $name", t))
            }
        }

        install("module resources") { addAssetPath(moduleApplicationInfo.sourceDir) }
        install("settings entry") { Edge.addSettingsButtonToToolbar() }

        val preference: Preference = try {
            val dataStoreFile =
                File(param.applicationInfo.dataDir, "files/datastore/${Preference.FILE_NAME}")
            Json.decodeFromString<Preference>(dataStoreFile.readText())
        } catch (_: Exception) {
            null
        } ?: return

        if (preference.hideStatusBar) {
            install("hide status bar") { Edge.hideStatusBar() }
        }
        install("remove edge-to-edge padding") {
            Edge.removePadding(preference.removeTopPadding, preference.removeBottomPadding)
        }
        if (preference.longClickOverflowButtonToTop) {
            install("overflow long-click scroll-to-top") {
                Edge.setupScrollToTopOnLongClickOverflowButton()
            }
        }
        if (preference.longClickNewTabButtonToLoadInplace) {
            install("new-tab long-click load-in-place") {
                Edge.setupLoadUrlOnLongClickNewTabButton(
                    if (preference.setNewTabPageUrl) preference.newTabPageUrl else "edge://newtab/"
                )
            }
        }
        if (preference.setNewTabPageUrl) {
            install("custom new-tab URL") { Edge.setNewTabPageUrl(preference.newTabPageUrl) }
        }
        if (preference.externalDownload) {
            install("external downloader") {
                Edge.externalDownload(
                    preference.blockOriginalDownloadDialog,
                    preference.setDefaultDownloader,
                    preference.defaultDownloaderType,
                    preference.defaultDownloaderPackageName
                )
            }
        }

        if (preference.longClickNewTabButtonToLoadInplace && preference.replaceNewTabPageWithHome) {
            install("replace new-tab button with home") { Edge.replaceNewTabPageWithHome() }
        }
        if (preference.clearBrowsingDataOnExit) {
            install("clear browsing data on exit") {
                Edge.clearBrowsingDataOnExit(
                    preference.clearBrowsingDataOnExitDataTypes.toIntArray(),
                    preference.clearBrowsingDataOnExitShouldClearTabs,
                    preference.clearBrowsingDataOnExitTimePeriod
                )
            }
        }
        if (preference.redirectCustomTab) {
            install("redirect custom tabs") { Edge.redirectCustomTab(param.packageName) }
        }
    }
}
