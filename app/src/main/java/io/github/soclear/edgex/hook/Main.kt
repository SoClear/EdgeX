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
        if (lpparam.processName != "com.microsoft.emmx" &&
            lpparam.processName != "com.microsoft.emmx.beta" &&
            lpparam.processName != "com.microsoft.emmx.canary" &&
            lpparam.processName != "com.microsoft.emmx.dev"
        ) {
            return
        }

        addAssetPath(modulePath)
        SettingsButton.addSettingsButtonToToolbar()
        Crx.crxInstallCompatibility()

        val preference: Preference = try {
            val dataStoreFile = File(lpparam.appInfo.dataDir, "files/datastore/${Preference.FILE_NAME}")
            Json.decodeFromString<Preference>(dataStoreFile.readText())
        } catch (_: Exception) {
            null
        } ?: return

        if (preference.hideStatusBar) {
            Ui.hideStatusBar()
        }
        Ui.removePadding(preference.removeTopPadding, preference.removeBottomPadding)
        if (preference.longClickOverflowButtonToTop) {
            LongClick.setupScrollToTopOnLongClickOverflowButton()
        }
        if (preference.longClickNewTabButtonToLoadInplace) {
            LongClick.setupLoadUrlOnLongClickNewTabButton(
                if (preference.setNewTabPageUrl) preference.newTabPageUrl else "edge://newtab/"
            )
        }
        if (preference.setNewTabPageUrl) {
            Ui.setNewTabPageUrl(preference.newTabPageUrl)
        }
        if (preference.externalDownload) {
            Download.externalDownload(
                preference.blockOriginalDownloadDialog,
                preference.setDefaultDownloader,
                preference.defaultDownloaderType,
                preference.defaultDownloaderPackageName
            )
        }

        if (preference.longClickNewTabButtonToLoadInplace && preference.replaceNewTabPageWithHome) {
            HomeButton.replaceNewTabPageWithHome()
        }
        if (preference.clearBrowsingDataOnExit) {
            BrowsingData.clearBrowsingDataOnExit(
                preference.clearBrowsingDataOnExitDataTypes.toIntArray(),
                preference.clearBrowsingDataOnExitShouldClearTabs,
                preference.clearBrowsingDataOnExitTimePeriod
            )
        }
        if (preference.redirectCustomTab) {
            CustomTab.redirectCustomTab(lpparam)
        }
    }
}
