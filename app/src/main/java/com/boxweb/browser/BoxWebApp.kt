package com.boxweb.browser

import android.app.Application
import com.boxweb.browser.data.BrowserRepository

class BoxWebApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BrowserRepository.init(this)
        DownloadStore.init(this)
        DownloadEngine.init(this)
        DownloadNotifications.createChannels(this)
    }
}
