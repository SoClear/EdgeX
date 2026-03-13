package io.github.soclear.edgex.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.soclear.edgex.data.Preference
import io.github.soclear.edgex.hook.util.addAssetPath
import kotlinx.serialization.json.Json
import java.io.File

class Main : IXposedHookLoadPackage, IXposedHookZygoteInit {
    private lateinit var modulePath: String

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.microsoft.emmx" &&
            lpparam.packageName != "com.microsoft.emmx.beta" &&
            lpparam.packageName != "com.microsoft.emmx.canary" &&
            lpparam.packageName != "com.microsoft.emmx.dev"
        ) {
            return
        }

        addAssetPath(modulePath)
        Edge.addSettingsButtonToToolbar()

        val preference: Preference = try {
            val dataStoreFile = File(lpparam.appInfo.dataDir, "files/datastore/${Preference.FILE_NAME}")
            Json.decodeFromString<Preference>(dataStoreFile.readText())
        } catch (_: Exception) {
            null
        } ?: return

        if (preference.hideStatusBar) {
            Edge.hideStatusBar()
        }
        Edge.removePadding(preference.removeTopPadding, preference.removeBottomPadding)
        if (preference.longClickOverflowButtonToTop) {
            Edge.setupScrollToTopOnLongClickOverflowButton()
        }
        if (preference.longClickNewTabButtonToLoadInplace) {
            Edge.setupLoadUrlOnLongClickNewTabButton(
                if (preference.setNewTabPageUrl) preference.newTabPageUrl else "edge://newtab/"
            )
        }
        if (preference.setNewTabPageUrl) {
            Edge.setNewTabPageUrl(preference.newTabPageUrl)
        }
        if (preference.externalDownload) {
            Edge.externalDownload(
                preference.blockOriginalDownloadDialog,
                preference.setDefaultDownloader,
                preference.defaultDownloaderType,
                preference.defaultDownloaderPackageName
            )
        }
    }
}
