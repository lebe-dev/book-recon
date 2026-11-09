package ru.tinyops.android.bookrecon.di

import ru.tinyops.android.bookrecon.core.fake.FakeBehavior
import ru.tinyops.android.bookrecon.core.fake.FakeFlibustaSource
import ru.tinyops.android.bookrecon.core.port.FlibustaSource

@Suppress("unused")
class FlibustaModule(private val app: AppContainer) {
    val flibustaSource: FlibustaSource by lazy { FakeFlibustaSource(behavior = FakeBehavior(FakeBehavior.UI_DELAY)) }
}
