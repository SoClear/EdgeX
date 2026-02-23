package io.github.soclear.edgex

import android.annotation.SuppressLint
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.datastore.dataStoreFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.soclear.edgex.ui.MainScreen
import io.github.soclear.edgex.ui.ModuleDisabledScreen
import io.github.soclear.edgex.ui.theme.EdgeXTheme
import kotlin.system.exitProcess

class MainActivity : ComponentActivity() {
    val preferenceFile by lazy { dataStoreFile("whatever") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setScreen()
    }

    @SuppressLint("SetWorldReadable")
    private fun setWorldReadable(): Boolean = preferenceFile.setReadable(true, false)

    @SuppressLint("SetWorldReadable")
    override fun onPause() {
        super.onPause()
        setWorldReadable()
    }

    private fun setSettingScreen() {
        val viewModel: MainViewModel by viewModels {
            MainViewModelFactory(this.application)
        }

        setContent {
            EdgeXTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(viewModel = viewModel, modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    private fun setModuleDisabledScreen() {
        setContent {
            EdgeXTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ModuleDisabledScreen(
                        onClickClose = { exitProcess(0) },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    private fun setScreen() {
        if (setWorldReadable()) {
            setSettingScreen()
        } else {
            setModuleDisabledScreen()
        }
    }
}

private class MainViewModelFactory(private val application: Application) :
    ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
