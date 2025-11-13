package com.h3110w0r1d.geekpaste.di

import android.content.Context
import com.h3110w0r1d.geekpaste.data.config.ConfigManager
import com.h3110w0r1d.geekpaste.model.AppViewModel
import com.h3110w0r1d.geekpaste.utils.BleManager
import com.h3110w0r1d.geekpaste.utils.CertManager
import com.h3110w0r1d.geekpaste.utils.WebServer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ViewModel {
    @Provides
    @Singleton
    fun provideViewModel(
        @ApplicationContext context: Context,
        bleManager: BleManager,
        webServer: WebServer,
        configManager: ConfigManager,
        certManager: CertManager,
    ): AppViewModel = AppViewModel(context, bleManager, webServer, configManager, certManager)
}
