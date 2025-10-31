package com.h3110w0r1d.geekpaste.di

import android.content.Context
import com.h3110w0r1d.geekpaste.data.config.ConfigManager
import com.h3110w0r1d.geekpaste.utils.BleManager
import com.h3110w0r1d.geekpaste.utils.CertManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BleManagerModule {
    @Provides
    @Singleton
    fun provideBleManager(
        @ApplicationContext context: Context,
        configManager: ConfigManager,
        certManager: CertManager,
    ): BleManager = BleManager(context, configManager, certManager)
}
