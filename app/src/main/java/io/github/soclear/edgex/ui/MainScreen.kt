package io.github.soclear.edgex.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.soclear.edgex.data.DownloaderType

@Composable
fun MainScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val preference by viewModel.preference.collectAsStateWithLifecycle()
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
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
            title = stringResource(id = R.string.remove_top_padding_title),
            checked = preference.removeTopPadding,
            onCheckedChange = {
                viewModel.updateData { currentPreference ->
                    currentPreference.copy(removeTopPadding = it)
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
                if (!it) {
                    viewModel.updateData { currentPreference ->
                        currentPreference.copy(replaceNewTabPageWithHome = false)
                    }
                }
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
        SwitchItem(
            title = stringResource(id = R.string.replace_new_tab_page_with_home_title),
            summary = stringResource(id = R.string.replace_new_tab_page_with_home_summary),
            checked = preference.replaceNewTabPageWithHome,
            onCheckedChange = {
                if (!preference.longClickNewTabButtonToLoadInplace) {
                    return@SwitchItem
                }
                viewModel.updateData { currentPreference ->
                    currentPreference.copy(replaceNewTabPageWithHome = it)
                }
            }
        )
        Column {
            var expanded by rememberSaveable { mutableStateOf(false) }

            SwitchItem(
                title = stringResource(id = R.string.download_external_title),
                summary = stringResource(id = R.string.download_external_summary),
                clickable = true,
                onClick = { expanded = !expanded },
                checked = preference.externalDownload,
                onCheckedChange = {
                    viewModel.updateData { currentPreference ->
                        currentPreference.copy(externalDownload = it)
                    }
                }
            )
            AnimatedVisibility(expanded && preference.externalDownload) {
                Column {
                    SwitchItem(
                        title = stringResource(id = R.string.download_block_original_download_dialog_title),
                        checked = preference.blockOriginalDownloadDialog,
                        onCheckedChange = {
                            viewModel.updateData { currentPreference ->
                                currentPreference.copy(blockOriginalDownloadDialog = it)
                            }
                        }
                    )
                    // 控制弹窗显示隐藏的状态
                    var showDialog by rememberSaveable { mutableStateOf(false) }

                    SwitchItem(
                        title = stringResource(id = R.string.download_set_default_downloader),
                        summary = if (preference.setDefaultDownloader) {
                            when (preference.defaultDownloaderType) {
                                DownloaderType.SYSTEM_DOWNLOADER -> stringResource(R.string.download_system)
                                DownloaderType.THIRD_PARTY_APP -> preference.defaultDownloaderPackageName
                            }
                        } else {
                            null
                        },
                        clickable = true,
                        onClick = {
                            if (preference.setDefaultDownloader) {
                                showDialog = true
                            }
                        },
                        checked = preference.setDefaultDownloader,
                        onCheckedChange = { isChecked ->
                            viewModel.updateData { currentPreference ->
                                currentPreference.copy(setDefaultDownloader = isChecked)
                            }
                        }
                    )

                    if (showDialog) {
                        DownloaderConfigDialog(
                            initialType = preference.defaultDownloaderType,
                            initialPackageName = preference.defaultDownloaderPackageName,
                            onDismiss = {
                                showDialog = false // 取消时只关闭弹窗
                            },
                            onConfirm = { newType, newPackageName ->
                                showDialog = false // 确认时关闭弹窗
                                // 保存数据到 DataStore
                                viewModel.updateData { currentPreference ->
                                    currentPreference.copy(
                                        defaultDownloaderType = newType,
                                        defaultDownloaderPackageName = newPackageName
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
        
        Column {
            var expanded by rememberSaveable { mutableStateOf(false) }

            SwitchItem(
                title = stringResource(id = R.string.clear_data_on_exit_title),
                summary = stringResource(id = R.string.clear_data_on_exit_summary),
                clickable = true,
                onClick = { expanded = !expanded },
                checked = preference.clearBrowsingDataOnExit,
                onCheckedChange = {
                    viewModel.updateData { currentPreference ->
                        currentPreference.copy(clearBrowsingDataOnExit = it)
                    }
                }
            )
            AnimatedVisibility(expanded && preference.clearBrowsingDataOnExit) {
                Column {
                    SwitchItem(
                        title = stringResource(id = R.string.clear_data_on_exit_close_tabs_title),
                        checked = preference.clearBrowsingDataOnExitShouldClearTabs,
                        onCheckedChange = {
                            viewModel.updateData { currentPreference ->
                                currentPreference.copy(clearBrowsingDataOnExitShouldClearTabs = it)
                            }
                        }
                    )
                    
                    var showTimePeriodDialog by rememberSaveable { mutableStateOf(false) }
                    var showDataTypesDialog by rememberSaveable { mutableStateOf(false) }

                    val timePeriodStr = when (preference.clearBrowsingDataOnExitTimePeriod) {
                        0 -> stringResource(R.string.time_range_last_hour)
                        1 -> stringResource(R.string.time_range_last_24_hours)
                        2 -> stringResource(R.string.time_range_last_7_days)
                        3 -> stringResource(R.string.time_range_last_4_weeks)
                        else -> stringResource(R.string.time_range_all_time)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showTimePeriodDialog = true }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.clear_data_on_exit_time_range_title),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = timePeriodStr,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDataTypesDialog = true }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.clear_data_on_exit_data_types_title),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = preference.clearBrowsingDataOnExitDataTypes.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (showTimePeriodDialog) {
                        TimePeriodConfigDialog(
                            initialPeriod = preference.clearBrowsingDataOnExitTimePeriod,
                            onDismiss = { showTimePeriodDialog = false },
                            onConfirm = { newPeriod ->
                                showTimePeriodDialog = false
                                viewModel.updateData { currentPreference ->
                                    currentPreference.copy(clearBrowsingDataOnExitTimePeriod = newPeriod)
                                }
                            }
                        )
                    }

                    if (showDataTypesDialog) {
                        DataTypesConfigDialog(
                            initialTypes = preference.clearBrowsingDataOnExitDataTypes,
                            onDismiss = { showDataTypesDialog = false },
                            onConfirm = { newTypes ->
                                showDataTypesDialog = false
                                viewModel.updateData { currentPreference ->
                                    currentPreference.copy(clearBrowsingDataOnExitDataTypes = newTypes)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DownloaderConfigDialog(
    initialType: DownloaderType,
    initialPackageName: String,
    onDismiss: () -> Unit,
    onConfirm: (DownloaderType, String) -> Unit
) {
    var tempType by remember { mutableStateOf(initialType) }
    var tempPackageName by remember { mutableStateOf(initialPackageName) }

    // 新增：记录输入框是否显示错误状态
    var isPackageNameError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.download_select_default_downloader))
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                DialogOptionRow(
                    text = stringResource(R.string.download_system),
                    selected = tempType == DownloaderType.SYSTEM_DOWNLOADER,
                    onClick = {
                        tempType = DownloaderType.SYSTEM_DOWNLOADER
                        isPackageNameError = false // 切换选项时清除错误
                    }
                )

                DialogOptionRow(
                    text = stringResource(R.string.download_specify_third_party_app),
                    selected = tempType == DownloaderType.THIRD_PARTY_APP,
                    onClick = { tempType = DownloaderType.THIRD_PARTY_APP }
                )

                AnimatedVisibility(
                    visible = tempType == DownloaderType.THIRD_PARTY_APP,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    OutlinedTextField(
                        value = tempPackageName,
                        onValueChange = {
                            tempPackageName = it
                            // 用户开始输入时，自动清除错误提示
                            if (it.isNotBlank()) isPackageNameError = false
                        },
                        label = { Text(stringResource(R.string.download_enter_package_name)) },
                        placeholder = { Text(stringResource(R.string.download_package_name_example)) },
                        singleLine = true,
                        // 绑定错误状态
                        isError = isPackageNameError,
                        // 错误时的底部提示文本
                        supportingText = {
                            if (isPackageNameError) {
                                Text(
                                    text = stringResource(R.string.download_package_name_empty_error),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, start = 8.dp, end = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // 校验逻辑：如果是第三方应用，且包名为空（或全是空格）
                    if (tempType == DownloaderType.THIRD_PARTY_APP && tempPackageName.isBlank()) {
                        isPackageNameError = true // 触发错误提示，拦截确认事件
                    } else {
                        isPackageNameError = false
                        // 校验通过，传出数据并关闭弹窗（修剪掉首尾多余空格）
                        onConfirm(tempType, tempPackageName.trim())
                    }
                }
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun DialogOptionRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
fun TimePeriodConfigDialog(
    initialPeriod: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var tempPeriod by remember { mutableStateOf(initialPeriod) }
    val options = listOf(
        0 to stringResource(R.string.time_range_last_hour),
        1 to stringResource(R.string.time_range_last_24_hours),
        2 to stringResource(R.string.time_range_last_7_days),
        3 to stringResource(R.string.time_range_last_4_weeks),
        4 to stringResource(R.string.time_range_all_time)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.clear_data_on_exit_time_range_title))
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (value, title) ->
                    DialogOptionRow(
                        text = title,
                        selected = tempPeriod == value,
                        onClick = { tempPeriod = value }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(tempPeriod) }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun DataTypesConfigDialog(
    initialTypes: List<Int>,
    onDismiss: () -> Unit,
    onConfirm: (List<Int>) -> Unit
) {
    var tempTypes by remember { mutableStateOf(initialTypes.toSet()) }
    val options = listOf(
        0 to stringResource(R.string.data_type_history),
        1 to stringResource(R.string.data_type_cookies),
        2 to stringResource(R.string.data_type_cache),
        3 to stringResource(R.string.data_type_passwords),
        4 to stringResource(R.string.data_type_form_data),
        5 to stringResource(R.string.data_type_site_settings)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.clear_data_on_exit_data_types_title))
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (value, title) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                tempTypes = if (tempTypes.contains(value)) {
                                    tempTypes - value
                                } else {
                                    tempTypes + value
                                }
                            }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = tempTypes.contains(value),
                            onCheckedChange = null
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(tempTypes.toList().sorted()) }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}