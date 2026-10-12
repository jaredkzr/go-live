package sh.kizer.golive

/**
 * Decides which broadcasts are new measurements. During Portable Sleep the Go
 * rebroadcasts its last measurement under the same packet id at every connect
 * window, and while awake it repeats each one several times a second.
 */
class ReadingFilter(private val repeatGapMs: Long = REPEAT_GAP_MS) {
    private var lastDevice: String? = null
    private var lastPacketId = -1
    private var lastTime = 0L

    /** True when this broadcast should be stored; remembers it if so. */
    fun accept(device: String, packetId: Int, time: Long): Boolean {
        val isNew = device != lastDevice || packetId != lastPacketId || time - lastTime > repeatGapMs
        if (isNew) remember(device, packetId, time)
        return isNew
    }

    /** Starts from the last stored reading, so a restart doesn't store a repeat. */
    fun remember(device: String, packetId: Int, time: Long) {
        lastDevice = device
        lastPacketId = packetId
        lastTime = time
    }

    companion object {
        /** The same packet id after this long is a new measurement (the id wraps at 256). */
        const val REPEAT_GAP_MS = 30 * 60 * 1000L
    }
}
