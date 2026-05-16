package io.github.soclear.edgex.data

import kotlinx.serialization.Serializable

@Serializable
data class Preference(
    val hideStatusBar: Boolean = false,
    val removeTopPadding: Boolean = false,
    val removeBottomPadding: Boolean = false,
    val longClickOverflowButtonToTop: Boolean = false,
    val longClickNewTabButtonToLoadInplace: Boolean = false,
    val setNewTabPageUrl: Boolean = false,
    val newTabPageUrl: String = "edge://newtab/",
    val replaceNewTabPageWithHome: Boolean = false,
    val externalDownload: Boolean = false,
    val blockOriginalDownloadDialog: Boolean = false,
    val setDefaultDownloader: Boolean = false,
    val defaultDownloaderType: DownloaderType = DownloaderType.SYSTEM_DOWNLOADER,
    val defaultDownloaderPackageName: String = "",
    val clearBrowsingDataOnExit: Boolean = false,
    val clearBrowsingDataOnExitDataTypes: List<Int> = listOf(0),
    val clearBrowsingDataOnExitShouldClearTabs: Boolean = false,
    val clearBrowsingDataOnExitTimePeriod: Int = 4,
    val redirectCustomTab: Boolean = false,
){
    companion object {
        const val FILE_NAME = "EdgeXPreference.json"
    }
}
