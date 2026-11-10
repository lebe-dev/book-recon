package ru.tinyops.android.bookrecon

import android.app.Application
import ru.tinyops.android.bookrecon.di.AppContainer

class BookReconApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.log.start()
        container.reports.start()
        container.server.start()
    }
}
