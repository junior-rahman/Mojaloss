package com.mojaloss.app.feature.player

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.TextureView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import com.mojaloss.app.BuildConfig
import com.mojaloss.core.model.MediaTitle
import com.mojaloss.core.model.PlaybackDescriptor
import com.mojaloss.core.model.PlaybackObservation
import com.mojaloss.core.model.PlaybackPhase
import com.mojaloss.core.model.SessionScope
import com.mojaloss.core.player.Media3MainPlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real Media3 extractor/decoder/clock with a generated eight-second MP4 in SimpleCache.
 * Every supplied cache source has upstream=null and no writable sink. No real service,
 * credentials, authenticated download, network transport, or downloaded customer video.
 */
@UnstableApi
class OfflinePlayerRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun pausedBufferingReleasesActualPlayerViewAwakeAndPublishesPaused() {
        withCache { cache, _ ->
            val entered = CountDownLatch(1)
            val resumeRead = CountDownLatch(1)
            val factory = DataSource.Factory {
                val delegate = cacheOnly(cache).createDataSource()
                object : DataSource by delegate {
                    override fun open(dataSpec: DataSpec): Long {
                        entered.countDown()
                        if (!resumeRead.await(10, TimeUnit.SECONDS)) throw IOException("Local fixture read gate timed out")
                        return delegate.open(dataSpec)
                    }
                }
            }
            withController(factory) { controller, observations ->
                lateinit var view: PlayerView
                var awake: PlayerScreenOnBinding? = null
                try {
                    onMain {
                        view = PlayerView(context)
                        view.player = controller.exoPlayer
                        awake = PlayerScreenOnBinding(view, controller.exoPlayer)
                        controller.prepare(descriptor(), autoPlay = true)
                    }
                    assertTrue("The actual cache read must start before the pause", entered.await(8, TimeUnit.SECONDS))
                    onMain {
                        assertEquals(Player.STATE_BUFFERING, controller.exoPlayer.playbackState)
                        assertTrue(view.keepScreenOn)
                        controller.pause()
                        assertEquals(PlaybackPhase.PAUSED, controller.snapshot.value.phase)
                        assertFalse(controller.exoPlayer.playWhenReady)
                        assertFalse(view.keepScreenOn)
                        assertTrue(observations.none { it.advancing || (it.observed && it.positionMs > 0) })
                        awake?.close()
                        assertFalse(view.keepScreenOn)
                        view.player = null
                    }
                } finally {
                    resumeRead.countDown()
                    onMain { awake?.close() }
                }
            }
        }
    }

    @Test fun completeCacheDecodesAnActualFrameAndAdvancesObservedPlaybackWithoutUpstream() {
        withCache { cache, _ ->
            val reads = AtomicInteger()
            val factory = DataSource.Factory {
                reads.incrementAndGet()
                cacheOnly(cache).createDataSource()
            }
            withController(factory) { controller, observations ->
                val frames = AtomicInteger()
                lateinit var texture: TextureView
                onMain {
                    controller.exoPlayer.addListener(object : Player.Listener {
                        override fun onRenderedFirstFrame() { frames.incrementAndGet() }
                    })
                }
                compose.setContent {
                    AndroidView(modifier = Modifier.fillMaxSize(),
                        factory = { viewContext -> TextureView(viewContext).also {
                            texture = it
                            controller.exoPlayer.setVideoTextureView(it)
                        } },
                        onRelease = { controller.exoPlayer.clearVideoTextureView(it) })
                }
                compose.waitForIdle()
                onMain { controller.prepare(descriptor(), autoPlay = true) }
                await("A real cached MP4 must render and its observed clock must advance", 20_000) {
                    frames.get() > 0 && controller.snapshot.value.lastAdvancingPositionMs > 0 && observations.any { it.advancing }
                }
                var rendered: Bitmap? = null
                onMain { rendered = texture.bitmap }
                assertNotNull("TextureView must expose the actual decoded frame", rendered)
                val bitmap = checkNotNull(rendered)
                assertTrue("Synthetic decoded frame must contain several colors", hasSeveralColors(bitmap))
                val evidence = File(context.filesDir, "ux-0.1.6-offline-player-fixtures").apply { mkdirs() }
                val output = File(evidence, "decoded-cache-only-frame.png")
                output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                File(evidence, "decoded-cache-only-frame.txt").writeText(
                    "app=${BuildConfig.VERSION_NAME}\nfixture=generated-eight-second-h264-mp4\n" +
                        "asset_sha256=44fcb06fe298749d12629ca690b6791a34ca1f44c7ea227042ec49a4a70d75a8\n" +
                        "capture=actual-decoded-TextureView-frame\ncache_upstream=none\n" +
                        "frame_count=${frames.get()}\ncustomer_media=false\n")
                bitmap.recycle()
                onMain {
                    controller.pause()
                    assertTrue(reads.get() > 0)
                    assertTrue(controller.snapshot.value.lastAdvancingPositionMs > 0)
                    assertTrue(observations.any { it.observed && it.advancing && it.positionMs > 0 })
                    controller.exoPlayer.clearVideoTextureView(texture)
                }
            }
        }
    }

    @Test fun missingFactoryAndMissingCacheSpansFailClosedAndRetryNeverInventsProgress() {
        // Missing factory is rejected synchronously before constructing any media source.
        withController(null) { controller, observations ->
            onMain {
                controller.prepare(descriptor(), autoPlay = true)
                assertEquals(PlaybackPhase.ERROR, controller.snapshot.value.phase)
                assertFalse(controller.canRetry)
                controller.retry()
                assertEquals(PlaybackPhase.ERROR, controller.snapshot.value.phase)
                assertTrue(observations.none { it.advancing || it.observed })
            }
        }
        val rejectedFactoryCalls = AtomicInteger()
        withController(DataSource.Factory {
            rejectedFactoryCalls.incrementAndGet()
            throw AssertionError("An invalid offline descriptor must not open a source")
        }) { controller, observations ->
            onMain {
                controller.prepare(descriptor().copy(headers = mapOf("X-Fixture" to "local-test-only")), autoPlay = true)
                assertEquals(PlaybackPhase.ERROR, controller.snapshot.value.phase)
                controller.retry()
                assertEquals(0, rejectedFactoryCalls.get())
                assertTrue(observations.none { it.advancing || it.observed })
            }
        }
        withCache { cache, _ ->
            val opened = AtomicInteger()
            val readOnlyFactory = DataSource.Factory {
                opened.incrementAndGet()
                // No upstream exists to contact if this key/range is absent.
                cacheOnly(cache).createDataSource()
            }
            withController(readOnlyFactory) { controller, observations ->
                onMain { controller.prepare(descriptor("missing-cache-key"), autoPlay = true) }
                await("Missing cached spans must produce local recovery", 12_000) {
                    controller.snapshot.value.phase == PlaybackPhase.ERROR
                }
                onMain {
                    assertTrue(controller.canRetry)
                    assertEquals(0L, controller.snapshot.value.lastAdvancingPositionMs)
                    controller.retry()
                }
                await("Retry must also fail from the same read-only cache", 12_000) {
                    controller.snapshot.value.phase == PlaybackPhase.ERROR
                }
                onMain {
                    assertFalse(controller.canRetry)
                    assertTrue(opened.get() >= 2)
                    assertEquals(0L, controller.snapshot.value.lastAdvancingPositionMs)
                    assertTrue(observations.none { it.advancing || it.observed })
                }
            }
        }
    }

    private fun withController(factory: DataSource.Factory?, block: (Media3MainPlayerController, List<PlaybackObservation>) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val observations = CopyOnWriteArrayList<PlaybackObservation>()
        lateinit var controller: Media3MainPlayerController
        onMain { controller = Media3MainPlayerController(context, scope, offlineDataSourceFactory = factory,
            onObservation = { observations += it }) }
        try { block(controller, observations) } finally {
            onMain { controller.release(); scope.cancel() }
        }
    }

    @Suppress("DEPRECATION")
    private fun withCache(block: (SimpleCache, File) -> Unit) {
        val parent = File(context.cacheDir, "offline-player-regression-${System.nanoTime()}").apply { mkdirs() }
        val directory = File(parent, "media")
        // The legacy index is sufficient for an isolated tiny native fixture and avoids
        // sharing the app's production DownloadManager database with tests.
        val cache = SimpleCache(directory, NoOpCacheEvictor())
        try {
            val bytes = instrumentation.context.assets.open("offline-player-fixture.mp4").use { it.readBytes() }
            val hole = checkNotNull(cache.startReadWriteNonBlocking(KEY, 0, bytes.size.toLong()))
            try {
                val span = cache.startFile(KEY, 0, bytes.size.toLong())
                span.writeBytes(bytes)
                cache.commitFile(span, bytes.size.toLong())
                cache.applyContentMetadataMutations(KEY,
                    ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it, bytes.size.toLong()) })
            } finally { cache.releaseHoleSpan(hole) }
            assertTrue(cache.isCached(KEY, 0, bytes.size.toLong()))
            block(cache, directory)
        } finally {
            cache.release()
            SimpleCache.delete(directory, null)
            // Leave only decoded evidence PNGs, never cache fragments/private media metadata.
            if (parent.listFiles().isNullOrEmpty()) parent.delete()
        }
    }

    private fun cacheOnly(cache: SimpleCache): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache).setUpstreamDataSourceFactory(null).setCacheWriteDataSinkFactory(null)

    private fun descriptor(key: String = KEY) = PlaybackDescriptor(
        title = MediaTitle("generated-offline-fixture", "Generated offline video fixture", runtimeMs = 8_000, isPlayable = true),
        streamUrl = "https://offline.example.test/Items/generated-offline-fixture/Download",
        sessionScope = SessionScope("https://offline.example.test", "fixture-account", 1),
        offlineCacheKey = key)

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync { action() }

    private fun await(description: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            var passed = false
            onMain { passed = condition() }
            if (passed) return
            SystemClock.sleep(25)
        }
        throw AssertionError(description)
    }

    private fun hasSeveralColors(bitmap: Bitmap): Boolean {
        val colors = mutableSetOf<Int>()
        for (y in 0 until bitmap.height step (bitmap.height / 12).coerceAtLeast(1))
            for (x in 0 until bitmap.width step (bitmap.width / 12).coerceAtLeast(1))
                colors += bitmap.getPixel(x, y)
        return colors.size > 8
    }

    private companion object { const val KEY = "generated-cache-mp4" }
}
