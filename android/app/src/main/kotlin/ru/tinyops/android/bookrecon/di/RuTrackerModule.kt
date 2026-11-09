package ru.tinyops.android.bookrecon.di

import ru.tinyops.android.bookrecon.core.fake.FakeBehavior
import ru.tinyops.android.bookrecon.core.fake.FakeRuTrackerClient
import ru.tinyops.android.bookrecon.core.port.RuTrackerClient

@Suppress("unused")
class RuTrackerModule(private val app: AppContainer) {
    val ruTrackerClient: RuTrackerClient by lazy { FakeRuTrackerClient(behavior = FakeBehavior(FakeBehavior.UI_DELAY)) }
}
