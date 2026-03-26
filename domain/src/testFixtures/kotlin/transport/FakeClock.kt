package transport

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class FakeClock(
    initialInstant: Instant
) : Clock() {
    private var currentInstant = initialInstant

    override fun instant() =
        currentInstant

    override fun getZone() =
        ZoneId.of("UTC")

    override fun withZone(zone: ZoneId) =
        this

    fun advanceBy(duration: Duration) {
        currentInstant = currentInstant.plus(duration)
    }
}
