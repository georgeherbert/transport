package transport

import kotlinx.serialization.json.Json

fun transportJson() =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        prettyPrint = true
    }
