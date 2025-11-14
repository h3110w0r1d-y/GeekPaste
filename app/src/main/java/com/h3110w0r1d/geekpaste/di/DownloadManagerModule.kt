package com.h3110w0r1d.geekpaste.di

import android.content.Context
import com.h3110w0r1d.geekpaste.data.config.ConfigManager
import com.h3110w0r1d.geekpaste.utils.DownloadManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DownloadManagerModule {
    @Provides
    @Singleton
    fun provideDownloadManager(
        @ApplicationContext context: Context,
        configManager: ConfigManager,
    ): DownloadManager = DownloadManager(context, configManager)
}
