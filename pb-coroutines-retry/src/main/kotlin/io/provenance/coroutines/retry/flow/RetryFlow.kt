package io.provenance.coroutines.retry.flow

import io.provenance.coroutines.retry.RetryStrategy
import io.provenance.coroutines.retry.defaultRetryStrategies
import io.provenance.coroutines.retry.invert
import io.provenance.coroutines.retry.store.RetryRecord
import io.provenance.coroutines.tryMap
import java.time.OffsetDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaDuration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import mu.KotlinLogging

internal val DEFAULT_RETRY_INTERVAL = 10.seconds

/**
 * Create a retrying [Flow].
 *
 * Using [FlowRetry.produceNext] as the data source, feed retry records into [FlowRetry.process].
 * * If successful, route through [FlowRetry.onSuccess].
 * * If failed, route through [FlowRetry.onFailure].
 *
 * Once a record is successfully processed, emit the data element out to the flow.
 */
@OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)
fun <T> retryFlow(
    flowRetry: FlowRetry<T>,
    retryInterval: Duration = DEFAULT_RETRY_INTERVAL,
    retryStrategies: List<RetryStrategy> = defaultRetryStrategies
): Flow<T> {
    val log = KotlinLogging.logger {}
    val strategies = retryStrategies.invert()

    return pollingFlow(retryInterval) {
        for (strategy in strategies) {
            val lastAttempted = OffsetDateTime.now().minus(strategy.value.lastAttempted.toJavaDuration())

            val onFailure: suspend (RetryRecord<T>, Throwable) -> Unit = { rec, it ->
                strategy.value.onFailure("", it)
                flowRetry.onFailure(rec)
            }

            flowRetry.produceNext(strategy.key, lastAttempted)
                .onStart {
                    log.trace { "${strategy.value.name} --> Retrying records in group:${strategy.key} lastAttempted:$lastAttempted" }
                }
                .map { it.copy(attempt = it.attempt.inc()) }
                .tryMap(onFailure) {
                    flowRetry.process(it.data, it.attempt)

                    log.debug { "retry succeeded on attempt:${it.attempt} rec:${it.data}" }
                    flowRetry.onSuccess(it)
                }
                .collect()
        }
    }
}
