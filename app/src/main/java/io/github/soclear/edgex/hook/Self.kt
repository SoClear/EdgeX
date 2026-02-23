package io.github.soclear.edgex.hook

import android.content.Context
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.soclear.edgex.BuildConfig
import io.github.soclear.edgex.hook.util.PreferenceProvider

object Self {
    fun enableDataStoreFileSharing(loadPackageParam: XC_LoadPackage.LoadPackageParam) {
        if (loadPackageParam.packageName != BuildConfig.APPLICATION_ID) return
        val callback = XC_MethodReplacement.returnConstant(PreferenceProvider.getPreferenceFile())

        XposedHelpers.findAndHookMethod(
            "androidx.datastore.core.DeviceProtectedDataStoreFile",
            loadPackageParam.classLoader,
            "deviceProtectedDataStoreFile",
            Context::class.java,
            String::class.java,
            callback
        )
        XposedHelpers.findAndHookMethod(
            "androidx.datastore.DataStoreFile",
            loadPackageParam.classLoader,
            "dataStoreFile",
            Context::class.java,
            String::class.java,
            callback
        )
    }
}