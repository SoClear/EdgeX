package io.github.soclear.edgex.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.soclear.edgex.MainViewModel
import io.github.soclear.edgex.R
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun MainScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val preference by viewModel.preference.collectAsStateWithLifecycle()

    Column(modifier = modifier) {
        SwitchItem(
            title = stringResource(id = R.string.hide_status_bar_title),
            summary = stringResource(id = R.string.hide_status_bar_summary),
            checked = preference.hideStatusBar,
            onCheckedChange = {
                viewModel.updateData { currentPreference ->
                    currentPreference.copy(hideStatusBar = it)
                }
            }
        )
        SwitchItem(
            title = stringResource(id = R.string.remove_bottom_padding_title),
            checked = preference.removeBottomPadding,
            onCheckedChange = {
                viewModel.updateData { currentPreference ->
                    currentPreference.copy(removeBottomPadding = it)
                }
            }
        )
        SwitchItem(
            title = stringResource(id = R.string.long_click_overflow_button_to_top_title),
            checked = preference.longClickOverflowButtonToTop,
            onCheckedChange = {
                viewModel.updateData { currentPreference ->
                    currentPreference.copy(longClickOverflowButtonToTop = it)
                }
            }
        )
        SwitchItem(
            title = stringResource(id = R.string.long_click_new_tab_button_to_load_inplace_title),
            summary = stringResource(id = R.string.long_click_new_tab_button_to_load_inplace_summary),
            checked = preference.longClickNewTabButtonToLoadInplace,
            onCheckedChange = {
                viewModel.updateData { currentPreference ->
                    currentPreference.copy(longClickNewTabButtonToLoadInplace = it)
                }
            }
        )
        Column {
            var expanded by rememberSaveable { mutableStateOf(false) }

            SwitchItem(
                title = stringResource(id = R.string.set_new_tab_page_url_title),
                modifier = Modifier.animateContentSize(),
                summary = if (preference.setNewTabPageUrl) {
                    preference.newTabPageUrl
                } else null,
                clickable = true,
                onClick = { expanded = !expanded },
                checked = preference.setNewTabPageUrl,
                onCheckedChange = {
                    if (it && preference.newTabPageUrl == "edge://newtab/") {
                        expanded = true
                    } else if (!it) {
                        expanded = false
                    }
                    viewModel.updateData { currentPreference ->
                        currentPreference.copy(setNewTabPageUrl = it)
                    }
                }
            )

            AnimatedVisibility(expanded && preference.setNewTabPageUrl) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    var tempNewTabPageUrl by remember {
                        mutableStateOf(preference.newTabPageUrl)
                    }
                    OutlinedTextField(
                        value = tempNewTabPageUrl,
                        onValueChange = { tempNewTabPageUrl = it },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            viewModel.updateData { currentPreference ->
                                currentPreference.copy(newTabPageUrl = tempNewTabPageUrl)
                            }

                        }
                    ) {
                        Text(text = stringResource(id = R.string.confirm))
                    }
                }
            }
        }
    }
}

@Composable
fun ModuleDisabledScreen(
    onClickClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = stringResource(R.string.module_disabled_tip))
        Button(
            onClick = onClickClose,
            modifier = Modifier.padding(top = 10.dp)
        ) {
            Text(text = stringResource(R.string.close))
        }
    }
}