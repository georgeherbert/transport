package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Result4k
import dev.forkhandles.result4k.Success
import strikt.api.Assertion
import strikt.assertions.isA

fun <T, E> Assertion.Builder<Result4k<T, E>>.isSuccess(): Assertion.Builder<T> =
    this
        .isA<Success<T>>()
        .get(Success<T>::value)

fun <T, E> Assertion.Builder<Result4k<T, E>>.isFailure(): Assertion.Builder<E> =
    this
        .isA<Failure<E>>()
        .get(Failure<E>::reason)
