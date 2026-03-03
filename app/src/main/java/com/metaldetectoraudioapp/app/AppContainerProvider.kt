package com.metaldetectoraudioapp.app

import android.content.Context

object AppContainerProvider {
    @Volatile
    private var appContainer: AppContainer? = null

    fun get(context: Context): AppContainer {
        return appContainer ?: synchronized(this) {
            appContainer ?: AppContainer(context.applicationContext).also { appContainer = it }
        }
    }
}
