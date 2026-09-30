package koshei.episode

import java.time.Instant

/**
 * Retention for an episode escalated before any valid policy was ever read (POLICY_MISSING at start), when there is no
 * `escalatedRetentionMs` to use. Long enough for a person to pick the episode up; the episode still always closes.
 */
const val FALLBACK_ESCALATED_RETENTION_MS: Long = 24L * 60 * 60 * 1000

/** The whole-episode deadline (design §5.1). */
fun episodeDeadline(startedAt: Instant, policy: PolicyTable): Instant = startedAt.plusMillis(policy.deadlines.episodeMs)

/** Every wait except ESCALATED wakes at `min(entered + its own budget, episode deadline)` (design §5.1). */
fun waitUntil(enteredAt: Instant, stateBudgetMs: Long, episodeDeadline: Instant): Instant =
    minOf(enteredAt.plusMillis(stateBudgetMs), episodeDeadline)

/** ESCALATED counts `escalatedRetentionMs` from its own entry, regardless of the episode deadline (design §5.1). */
fun escalatedUntil(enteredAt: Instant, policy: PolicyTable?): Instant =
    enteredAt.plusMillis(policy?.deadlines?.escalatedRetentionMs ?: FALLBACK_ESCALATED_RETENTION_MS)

/**
 * An approval (or, for person-task kinds, a proposal) is valid strictly before `basis + approvalValidityMs`
 * (design §5.1).
 */
fun approvalStillValid(basis: Instant, now: Instant, policy: PolicyTable): Boolean =
    now.isBefore(basis.plusMillis(policy.deadlines.approvalValidityMs))
