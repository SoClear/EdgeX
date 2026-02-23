package io.github.soclear.edgex.data

import kotlinx.serialization.Serializable

@Serializable
data class Preference(
    val hideStatusBar: Boolean = false,
    val removeBottomPadding: Boolean = false,
    val longClickOverflowButtonToTop: Boolean = false,
    val longClickNewTabButtonToLoadInplace: Boolean = false,
    val setNewTabPageUrl: Boolean = false,
    val newTabPageUrl: String = "edge://newtab/",
)
