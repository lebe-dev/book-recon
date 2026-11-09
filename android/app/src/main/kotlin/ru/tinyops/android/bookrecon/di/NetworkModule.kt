package ru.tinyops.android.bookrecon.di

import ru.tinyops.android.bookrecon.core.fake.FakeBehavior
import ru.tinyops.android.bookrecon.core.fake.FakeHttpClient
import ru.tinyops.android.bookrecon.core.port.HttpClient

@Suppress("unused")
class NetworkModule(private val app: AppContainer) {
    val httpClient: HttpClient by lazy { FakeHttpClient(FakeBehavior(FakeBehavior.UI_DELAY)) }
}
