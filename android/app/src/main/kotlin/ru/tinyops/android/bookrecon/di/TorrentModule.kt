package ru.tinyops.android.bookrecon.di

import ru.tinyops.android.bookrecon.core.fake.FakeTorrentEngine
import ru.tinyops.android.bookrecon.core.port.TorrentEngine

@Suppress("unused")
class TorrentModule(private val app: AppContainer) {
    val torrentEngine: TorrentEngine by lazy { FakeTorrentEngine() }
}
