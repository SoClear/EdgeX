package io.github.soclear.edgex

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.soclear.edgex.data.Preference
import io.github.soclear.edgex.ui.dataStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : ViewModel() {
    private val dataStore: DataStore<Preference> = application.dataStore

    val preference = dataStore.data.stateIn(
        scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = Preference()
    )

    fun updateData(nextPreference: (currentPreference: Preference) -> Preference) {
        viewModelScope.launch {
            dataStore.updateData {
                nextPreference(it)
            }
        }
    }
}

class MainViewModelFactory(private val application: Application) :
    ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
