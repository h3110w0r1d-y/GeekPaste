package com.h3110w0r1d.geekpaste.di

import android.content.Context
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
object WebServerModule {
    @Provides
    @Singleton
    fun provideWebServer(
        @ApplicationContext context: Context,
        certManager: CertManager,
    ): WebServer = WebServer(0, certManager, context) // 端口 0 表示使用随机可用端口
}
