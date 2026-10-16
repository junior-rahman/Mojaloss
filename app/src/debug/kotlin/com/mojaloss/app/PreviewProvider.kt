package com.mojaloss.app

import com.mojaloss.core.data.DemoMediaRepository
import com.mojaloss.core.model.MediaRepository
import kotlinx.coroutines.CoroutineScope

internal object PreviewProvider {
    fun create(@Suppress("UNUSED_PARAMETER") scope: CoroutineScope): MediaRepository = DemoMediaRepository()
}

