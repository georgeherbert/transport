package transport

@JvmInline
value class EnvironmentVariableName(val value: String)

object EnvironmentVariables {
    val tflSubscriptionKey = EnvironmentVariableName("TFL_SUBSCRIPTION_KEY")
}

fun Map<String, String>.requiredEnvironmentValue(name: EnvironmentVariableName) =
    getOrDefault(name.value, "").ifBlank {
        throw IllegalArgumentException("Missing required environment variable ${name.value}.")
    }
