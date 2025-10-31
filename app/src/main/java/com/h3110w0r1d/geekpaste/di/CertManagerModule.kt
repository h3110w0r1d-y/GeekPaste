package com.h3110w0r1d.geekpaste.di

import android.content.Context
import com.h3110w0r1d.geekpaste.utils.CertManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CertManagerModule {
    @Provides
    @Singleton
    fun provideCertManager(
        @ApplicationContext context: Context,
    ): CertManager = CertManager(context)
}
