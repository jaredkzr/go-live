package sh.kizer.golive.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.kizer.golive.Bucket
import sh.kizer.golive.Cbor
import sh.kizer.golive.GoConfig
import sh.kizer.golive.LinearCorrection
import sh.kizer.golive.Smoothing
import sh.kizer.golive.Csv
import sh.kizer.golive.Metric
import sh.kizer.golive.ReadingStore
import sh.kizer.golive.TrendRange
import sh.kizer.golive.Window
import sh.kizer.golive.repository
import kotlin.math.abs

/**
 * A trend chart's data: raw buckets over a window, the same buckets smoothed
 * for drawing (index for index), and the window's raw aggregates.
 */
data class Trend(
    val metric: Metric,
    val range: TrendRange,
    val window: Window,
    val buckets: List<Bucket>,
    val smoothed: List<Bucket>,
    val summary: Bucket?,
)

/** A tile's last 24 hours, smoothed like the trend charts. */
data class Spark(val window: Window, val buckets: List<Bucket>)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val repo = app.repository
    private val store = repo.store

    /** Ticks every second for "updated 12 s ago". */
    val now: StateFlow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), System.currentTimeMillis())

    /** The last 24 h of each metric, for the tiles. */
    val sparklines: StateFlow<Map<Metric, Spark>> = repo.dataVersion
        .mapLatest {
            val w = TrendRange.DAY_1.window(System.currentTimeMillis())
            Metric.entries.associateWith { Spark(w, Smoothing.smooth(store.series(it, w), w.bucketMs)) }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val trendMetric = MutableStateFlow(Metric.PM25)
    val trendRange = MutableStateFlow(TrendRange.DAY_1)

    val trend: StateFlow<Trend?> = combine(trendMetric, trendRange, repo.dataVersion) { m, r, _ -> m to r }
        .mapLatest { (metric, range) ->
            val w = range.window(System.currentTimeMillis())
            val buckets = store.series(metric, w)
            Trend(metric, range, w, buckets, Smoothing.smooth(buckets, w.bucketMs), store.summary(metric, w))
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val stats: StateFlow<ReadingStore.Stats?> = repo.dataVersion
        .mapLatest { store.stats(getApplication()) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Status of the last export, shown in Settings. */
    val exportMessage = MutableStateFlow<String?>(null)

    fun export(uri: Uri) {
        viewModelScope.launch {
            exportMessage.value = "Exporting…"
            exportMessage.value = try {
                val rows = withContext(Dispatchers.IO) {
                    var n = 0
                    getApplication<Application>().contentResolver.openOutputStream(uri)!!.bufferedWriter().use { out ->
                        out.appendLine(Csv.HEADER)
                        store.forEach {
                            out.appendLine(Csv.row(it))
                            n++
                        }
                    }
                    n
                }
                "Exported $rows readings."
            } catch (e: Exception) {
                "Export failed: ${e.message}"
            }
        }
    }

    fun clearData() = repo.clearData()

    // ---- The Go's temperature correction (Settings) ----

    sealed interface CorrectionState {
        data object Idle : CorrectionState
        data class Working(val message: String) : CorrectionState
        data class Loaded(val correction: LinearCorrection) : CorrectionState
        data class Saved(val correction: LinearCorrection) : CorrectionState
        data class Failed(val message: String) : CorrectionState
    }

    val correction = MutableStateFlow<CorrectionState>(CorrectionState.Idle)
    private var correctionJob: Job? = null

    /** Connects to the Go and reads its temperature correction. */
    fun readCorrection() = goConfig("Connecting to the Go…") { config ->
        val c = config.readTemperatureCorrection()
        repo.setTemperatureCorrection(c, System.currentTimeMillis())
        CorrectionState.Loaded(c)
    }

    /** Sets the intercept (°C), keeping the Go's scale; zero with scale 1 turns the correction off. */
    fun saveCorrection(current: LinearCorrection, interceptC: Double) = goConfig("Saving…") { config ->
        val scale = if (current.custom) current.scale else 1.0
        val wanted = if (abs(interceptC) < 0.005 && abs(scale - 1.0) < 1e-6) {
            LinearCorrection.NONE
        } else {
            LinearCorrection(custom = true, scale = scale, intercept = interceptC)
        }
        val result = config.writeTemperatureCorrection(wanted)
        repo.setTemperatureCorrection(result, System.currentTimeMillis())
        if (result.custom != wanted.custom || abs(result.intercept - wanted.intercept) > 0.01) {
            CorrectionState.Failed("The Go didn't accept the change.")
        } else {
            CorrectionState.Saved(result)
        }
    }

    fun closeCorrection() {
        correctionJob?.cancel()
        correction.value = CorrectionState.Idle
    }

    private fun goConfig(message: String, block: suspend (GoConfig) -> CorrectionState) {
        val address = repo.device.value
        if (address == null) {
            correction.value = CorrectionState.Failed("Go Live hasn't found your Go yet.")
            return
        }
        correctionJob?.cancel()
        correctionJob = viewModelScope.launch {
            correction.value = CorrectionState.Working(message)
            correction.value = try {
                block(GoConfig(getApplication(), address))
            } catch (e: GoConfig.GoConfigException) {
                CorrectionState.Failed(e.message ?: "Something went wrong.")
            } catch (e: SecurityException) {
                CorrectionState.Failed("Go Live needs the Nearby devices permission to change the Go's settings.")
            } catch (e: Cbor.FormatException) {
                CorrectionState.Failed("The Go's reply didn't make sense (${e.message}).")
            }
        }
    }
}
