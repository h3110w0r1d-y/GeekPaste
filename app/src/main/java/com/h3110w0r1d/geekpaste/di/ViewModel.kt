package com.h3110w0r1d.geekpaste.di

import android.content.Context
import com.h3110w0r1d.geekpaste.data.ConfigManager
import com.h3110w0r1d.geekpaste.model.AppViewModel
import com.h3110w0r1d.geekpaste.utils.BleManager
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
        configManager: ConfigManager,
    ): AppViewModel =
        AppViewModel(
            context,
            bleManager,
            configManager,
        )
}
