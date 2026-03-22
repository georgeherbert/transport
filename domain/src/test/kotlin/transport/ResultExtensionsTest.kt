package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import kotlin.test.Test
import strikt.api.expectThat
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo

class ResultExtensionsTest {
    @Test
    fun `failFast returns all success values when every result succeeds`() {
        val result = listOf(
            Success(1),
            Success(2),
            Success(3)
        ).failFast()

        expectThat(result).isSuccess().hasSize(3)
    }

    @Test
    fun `failFast returns the first failure`() {
        val result = listOf(
            Success(1),
            Failure(TransportError.UpstreamPayloadFailure("/endpoint", "broken")),
            Success(3)
        ).failFast()

        expectThat(result)
            .isFailure()
            .isEqualTo(TransportError.UpstreamPayloadFailure("/endpoint", "broken"))
    }
}
