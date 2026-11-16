package app.tono

import android.app.Application
import android.content.Context
import app.tono.audio.LocalAudio
import app.tono.audio.ToneExporter
import app.tono.audio.ToneStore
import app.tono.data.NewPipeDownloader
import app.tono.data.PrefsRepository
import app.tono.data.UpdateRepository
import app.tono.data.YouTubeRepository
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Hand-wired dependencies; small enough not to need a DI framework. */
class AppContainer(context: Context) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    val prefs = PrefsRepository(context)
    val youTube = YouTubeRepository(http, context.cacheDir)
    val localAudio = LocalAudio(context, context.cacheDir)
    val exporter = ToneExporter(context)
    val toneStore = ToneStore(context)
    val updates = UpdateRepository(context, http)
}

class TonoApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val locale = Locale.getDefault()
        NewPipe.init(
            NewPipeDownloader(container.http),
            Localization.fromLocale(locale),
            ContentCountry(locale.country.ifEmpty { "US" }),
        )
        // Housekeeping: nothing is downloading yet at process start, so leftovers are safe to drop.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { container.youTube.cleanupPartials() }
            runCatching { container.localAudio.cleanup() }
            runCatching { container.updates.cleanup() }
        }
    }
}

val Context.container: AppContainer get() = (applicationContext as TonoApp).container
