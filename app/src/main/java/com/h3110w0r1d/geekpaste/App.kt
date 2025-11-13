package com.h3110w0r1d.geekpaste

import android.app.Application
import android.util.Log
import com.h3110w0r1d.geekpaste.model.AppViewModel
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class App : Application() {
    @Inject
    lateinit var appViewModel: AppViewModel

    override fun onCreate() {
        super.onCreate()
        Log.d("App", "onCreate")
        Log.d("App", "appViewModel: $appViewModel")
    }
}
