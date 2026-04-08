package com.h3110w0r1d.geekpaste

import android.app.Application
import com.h3110w0r1d.geekpaste.model.AppViewModel
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class App : Application() {
    @Inject
    lateinit var appViewModel: AppViewModel
}
