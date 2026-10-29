package com.ramish.sadiq.di

import android.content.Context
import com.ramish.sadiq.ui.intercept.InterceptCopy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object InterceptModule {

    @Provides
    @Singleton
    fun provideInterceptCopy(@ApplicationContext context: Context): InterceptCopy =
        InterceptCopy(context)
}
