package transport

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler

class StaticJsonHandler(
    private val status: Int,
    private val body: String
) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { output ->
            output.write(bytes)
        }
    }
}
