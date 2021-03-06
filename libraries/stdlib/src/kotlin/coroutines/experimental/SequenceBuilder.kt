/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:kotlin.jvm.JvmMultifileClass
@file:kotlin.jvm.JvmName("SequenceBuilderKt")
package kotlin.coroutines.experimental

import kotlin.coroutines.experimental.intrinsics.*

/**
 * Builds a [Sequence] lazily yielding values one by one.
 *
 * @see kotlin.sequences.generateSequence
 *
 * @sample samples.collections.Sequences.Building.buildSequenceYieldAll
 * @sample samples.collections.Sequences.Building.buildFibonacciSequence
 */
@SinceKotlin("1.1")
public fun <T> buildSequence(builderAction: suspend SequenceBuilder<T>.() -> Unit): Sequence<T> = Sequence { buildIterator(builderAction) }

/**
 * Builds an [Iterator] lazily yielding values one by one.
 *
 * @sample samples.collections.Sequences.Building.buildIterator
 */
@SinceKotlin("1.1")
public fun <T> buildIterator(builderAction: suspend SequenceBuilder<T>.() -> Unit): Iterator<T> {
    val iterator = SequenceBuilderIterator<T>()
    iterator.nextStep = builderAction.createCoroutineUnchecked(receiver = iterator, completion = iterator)
    return iterator
}

/**
 * Builder for a [Sequence] or an [Iterator], provides [yield] and [yieldAll] suspension functions.
 *
 * @see buildSequence
 * @see buildIterator
 *
 * @sample samples.collections.Sequences.Building.buildSequenceYieldAll
 * @sample samples.collections.Sequences.Building.buildFibonacciSequence
 */
@RestrictsSuspension
@SinceKotlin("1.1")
public abstract class SequenceBuilder<in T> internal constructor() {
    /**
     * Yields a value to the [Iterator] being built.
     *
     * @sample samples.collections.Sequences.Building.buildSequenceYieldAll
     * @sample samples.collections.Sequences.Building.buildFibonacciSequence
     */
    public abstract suspend fun yield(value: T)

    /**
     * Yields all values from the `iterator` to the [Iterator] being built.
     *
     * The sequence of values returned by the given iterator can be potentially infinite.
     *
     * @sample samples.collections.Sequences.Building.buildSequenceYieldAll
     */
    public abstract suspend fun yieldAll(iterator: Iterator<T>)

    /**
     * Yields a collections of values to the [Iterator] being built.
     *
     * @sample samples.collections.Sequences.Building.buildSequenceYieldAll
     */
    public suspend fun yieldAll(elements: Iterable<T>) {
        if (elements is Collection && elements.isEmpty()) return
        return yieldAll(elements.iterator())
    }

    /**
     * Yields potentially infinite sequence of values  to the [Iterator] being built.
     *
     * The sequence can be potentially infinite.
     *
     * @sample samples.collections.Sequences.Building.buildSequenceYieldAll
     */
    public suspend fun yieldAll(sequence: Sequence<T>) = yieldAll(sequence.iterator())
}

private typealias State = Int
private const val State_NotReady: State = 0
private const val State_ManyNotReady: State = 1
private const val State_ManyReady: State = 2
private const val State_Ready: State = 3
private const val State_Done: State = 4
private const val State_Failed: State = 5

private class SequenceBuilderIterator<T> : SequenceBuilder<T>(), Iterator<T>, Continuation<Unit> {
    private var state = State_NotReady
    private var nextValue: T? = null
    private var nextIterator: Iterator<T>? = null
    var nextStep: Continuation<Unit>? = null

    override fun hasNext(): Boolean {
        while (true) {
            when (state) {
                State_NotReady -> {}
                State_ManyNotReady ->
                    if (nextIterator!!.hasNext()) {
                        state = State_ManyReady
                        return true
                    } else {
                        nextIterator = null
                    }
                State_Done -> return false
                State_Ready, State_ManyReady -> return true
                else -> throw exceptionalState()
            }

            state = State_Failed
            val step = nextStep!!
            nextStep = null
            step.resume(Unit)
        }
    }

    override fun next(): T {
        when (state) {
            State_NotReady, State_ManyNotReady -> return nextNotReady()
            State_ManyReady -> {
                state = State_ManyNotReady
                return nextIterator!!.next()
            }
            State_Ready -> {
                state = State_NotReady
                val result = nextValue as T
                nextValue = null
                return result
            }
            else -> throw exceptionalState()
        }
    }

    private fun nextNotReady(): T {
        if (!hasNext()) throw NoSuchElementException() else return next()
    }

    private fun exceptionalState(): Throwable = when (state) {
        State_Done -> NoSuchElementException()
        State_Failed -> IllegalStateException("Iterator has failed.")
        else -> IllegalStateException("Unexpected state of the iterator: $state")
    }


    suspend override fun yield(value: T) {
        nextValue = value
        state = State_Ready
        return suspendCoroutineOrReturn { c ->
            nextStep = c
            COROUTINE_SUSPENDED
        }
    }

    suspend override fun yieldAll(iterator: Iterator<T>) {
        if (!iterator.hasNext()) return
        nextIterator = iterator
        state = State_ManyReady
        return suspendCoroutineOrReturn { c ->
            nextStep = c
            COROUTINE_SUSPENDED
        }
    }

    // Completion continuation implementation
    override fun resume(value: Unit) {
        state = State_Done
    }

    override fun resumeWithException(exception: Throwable) {
        throw exception // just rethrow
    }

    override val context: CoroutineContext
        get() = EmptyCoroutineContext
}
