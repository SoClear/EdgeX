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
    val externalDownload: Boolean = false,
    val blockOriginalDownloadDialog: Boolean = false,
    val setDefaultDownloader: Boolean = false,
    val defaultDownloaderType: DownloaderType = DownloaderType.SYSTEM_DOWNLOADER,
    val defaultDownloaderPackageName: String = ""
)
