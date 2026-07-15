package com.example.notification

import com.example.repository.AssignmentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.math.min
import kotlin.math.pow

private const val MAX_ATTEMPTS = 5
private const val POLL_INTERVAL_MS = 2_000L
private const val BASE_BACKOFF_MS = 500L
private const val MAX_BACKOFF_MS = 10_000L

// Polls assignments left as PENDING_NOTIFICATION (the outbox) and retries delivery
// with exponential backoff, so a flaky notification never causes a lost lead:
// the assignment itself was already persisted before this worker ever runs.
class NotificationOutboxWorker(
    private val assignmentRepository: AssignmentRepository,
    private val agentNotifier: AgentNotifier
) {
    private val logger = LoggerFactory.getLogger(NotificationOutboxWorker::class.java)

    fun start(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                processPendingNotifications()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun processPendingNotifications() {
        for (assignment in assignmentRepository.findPendingNotifications()) {
            try {
                delayForAttempt(assignment.notificationAttempts)
                agentNotifier.notify(assignment)
                assignmentRepository.markNotified(assignment.id)
                logger.info("Notified agent for assignment {} after {} attempt(s)", assignment.id, assignment.notificationAttempts + 1)
            } catch (e: NotificationDeliveryException) {
                assignmentRepository.recordFailedAttempt(assignment.id, MAX_ATTEMPTS)
                val attempts = assignment.notificationAttempts + 1
                if (attempts >= MAX_ATTEMPTS) {
                    logger.warn("Giving up on assignment {} after {} failed attempts", assignment.id, attempts)
                } else {
                    logger.info("Notification attempt {} failed for assignment {}, will retry", attempts, assignment.id)
                }
            }
        }
    }

    private suspend fun delayForAttempt(attempt: Int) {
        if (attempt == 0) return
        val backoff = (BASE_BACKOFF_MS * 2.0.pow(attempt - 1)).toLong()
        delay(min(backoff, MAX_BACKOFF_MS))
    }
}
