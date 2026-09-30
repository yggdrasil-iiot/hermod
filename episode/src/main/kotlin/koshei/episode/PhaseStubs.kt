package koshei.episode

import java.time.Instant

// Placeholders: Task 4 of plan B2b-2a replaces the first three, Task 5 the last two and deletes this file.
internal fun Out.onRevalidating(event: EpisodeEvent, policy: PolicyTable, now: Instant) = ignore(event, "not implemented")
internal fun Out.onUnknownPrecondition(event: EpisodeEvent, policy: PolicyTable, now: Instant) = ignore(event, "not implemented")
