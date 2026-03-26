package transport

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import java.util.concurrent.atomic.AtomicReference

class RecordingJsonHandler(
    private val observedQuery: AtomicReference<String?>,
    private val status: Int,
    private val body: String
) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        observedQuery.set(exchange.requestURI.query)
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { output ->
            output.write(bytes)
        }
    }
}
