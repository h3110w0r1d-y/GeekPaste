package com.h3110w0r1d.geekpaste.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.h3110w0r1d.geekpaste.data.config.ConfigManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ConfigManagerModule {
    @Provides
    @Singleton
    fun provideAppConfigManager(dataStore: DataStore<Preferences>): ConfigManager = ConfigManager(dataStore)
}
