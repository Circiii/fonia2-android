package tech.fonia.face

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Runs a suspending call to the end; `:face` has no coroutine library and needs none. */
fun <T> runSuspend(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    val done = CountDownLatch(1)
    block.startCoroutine(
        Continuation(EmptyCoroutineContext) {
            outcome = it
            done.countDown()
        },
    )
    check(done.await(60, TimeUnit.SECONDS)) { "timed out" }
    return outcome!!.getOrThrow()
}

// FoniaFaceTests/FaceTests.swift helpers.

fun rotationY(degrees: Float): Float4x4 {
    val a = degrees * PI_F / 180f
    return Float4x4(
        Float4(cos(a), 0f, -sin(a), 0f),
        Float4(0f, 1f, 0f, 0f),
        Float4(sin(a), 0f, cos(a), 0f),
        Float4(0f, 0f, 0f, 1f),
    )
}

fun rotationX(degrees: Float): Float4x4 {
    val a = degrees * PI_F / 180f
    return Float4x4(
        Float4(1f, 0f, 0f, 0f),
        Float4(0f, cos(a), sin(a), 0f),
        Float4(0f, -sin(a), cos(a), 0f),
        Float4(0f, 0f, 0f, 1f),
    )
}

fun isClose(a: Float, b: Float, within: Float = 0.01f): Boolean = abs(a - b) <= within
