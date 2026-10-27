package com.cirabit.watch

import android.app.Application
import com.cirabit.android.mesh.PowerManager
import com.cirabit.watch.notification.WearNotificationCoordinator
import com.cirabit.watch.ui.WearPeerIdentityState

class CirabitWatchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PowerManager.getInstance(applicationContext)
        WearNotificationCoordinator.getInstance(applicationContext)
        WearPeerIdentityState.initialize(applicationContext)
    }
}
