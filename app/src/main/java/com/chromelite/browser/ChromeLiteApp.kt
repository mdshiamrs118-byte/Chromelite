package com.chromelite.browser

import android.app.Application
import com.chromelite.browser.data.BrowserRepository

class ChromeLiteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BrowserRepository.init(this)
        DownloadStore.init(this)
        DownloadEngine.init(this)
        DownloadNotifications.createChannels(this)
    }
}
