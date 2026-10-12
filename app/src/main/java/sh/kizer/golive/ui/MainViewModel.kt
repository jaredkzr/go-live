package sh.kizer.golive.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import sh.kizer.golive.ChartOptions
import sh.kizer.golive.Smoothing
import sh.kizer.golive.Csv
import sh.kizer.golive.Metric
import sh.kizer.golive.ReadingStore
import sh.kizer.golive.TrendRange
import sh.kizer.golive.Window
import sh.kizer.golive.repository

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
    val options: ChartOptions,
)

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

    /** The last 24 h of each metric in 15-minute buckets, for the tiles. */
    val sparklines: StateFlow<Map<Metric, List<Bucket>>> = repo.dataVersion
        .mapLatest {
            val w = TrendRange.DAY_1.window(System.currentTimeMillis(), SPARK_BUCKET_MS)
            Metric.entries.associateWith { store.series(it, w) }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val trendMetric = MutableStateFlow(Metric.PM25)
    val trendRange = MutableStateFlow(TrendRange.DAY_1)

    val trend: StateFlow<Trend?> = combine(trendMetric, trendRange, repo.chartOptions, repo.dataVersion) { m, r, o, _ ->
        Triple(m, r, o)
    }
        .mapLatest { (metric, range, options) ->
            val w = range.window(System.currentTimeMillis(), options.resolution)
            val buckets = store.series(metric, w)
            val smoothed = Smoothing.smooth(buckets, w.bucketMs, options.method, options.strength.window)
            Trend(metric, range, w, buckets, smoothed, store.summary(metric, w), options)
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

    companion object {
        private const val SPARK_BUCKET_MS = 15 * 60 * 1000L
    }
}
