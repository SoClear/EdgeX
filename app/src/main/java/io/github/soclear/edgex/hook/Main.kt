package io.github.soclear.edgex.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.soclear.edgex.BuildConfig
import io.github.soclear.edgex.hook.util.PreferenceProvider

class Main : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == BuildConfig.APPLICATION_ID) {
            Self.enableDataStoreFileSharing(lpparam)
        }

        val preference = PreferenceProvider.preference ?: return

        if (lpparam.packageName != "com.microsoft.emmx") {
            return
        }
        if (preference.hideStatusBar) {
            Edge.hideStatusBar()
        }
        if (preference.removeBottomPadding) {
            Edge.removeBottomPadding()
        }
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
    }
}
