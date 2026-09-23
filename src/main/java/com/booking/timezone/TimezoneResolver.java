package com.booking.timezone;

import java.time.*;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;

/**
 * Converts local wall-clock times to UTC Instants, handling DST edge cases.
 *
 * Two edge cases exist at DST boundaries:
 *   1. Gap (spring-forward): a local time doesn't exist at all.
 *      e.g. US/Eastern 2026-03-08 02:30 — clocks jump 02:00 → 03:00.
 *      Resolution: shift forward by the gap duration → 03:30 that day only.
 *
 *   2. Overlap (fall-back): a local time is ambiguous — it occurs twice.
 *      e.g. US/Eastern 2026-11-01 01:30 — clocks fall back 02:00 → 01:00.
 *      Resolution: pick the FIRST occurrence (the one before the rollback),
 *      which is the earlier UTC instant. This matches Google/Outlook behavior
 *      and avoids double-firing a recurring meeting.
 *
 * Both are handled through java.time's ZoneRules API, which exposes the
 * IANA tz database transitions directly — no manual offset arithmetic.
 */
public class TimezoneResolver {

    /**
     * Convert a local datetime to a UTC Instant in the given timezone.
     * Handles gaps (spring-forward) and overlaps (fall-back) automatically.
     *
     * @param localDateTime the wall-clock time the user intended
     * @param zoneId        IANA zone id, e.g. "America/New_York"
     * @return the corresponding UTC instant
     */
    public Instant toUtc(LocalDateTime localDateTime, ZoneId zoneId) {
        ZoneRules rules = zoneId.getRules();

        // Check if this local time falls in a gap or overlap.
        // For normal times (neither gap nor overlap), transition is null
        // and we fall through to the simple path.
        ZoneOffsetTransition transition = rules.getTransition(localDateTime);

        if (transition == null) {
            // Normal case — this local time is unambiguous and exists.
            // ZonedDateTime.of picks the only valid offset.
            return localDateTime.atZone(zoneId).toInstant();
        }

        if (transition.isGap()) {
            // GAP (spring-forward): localDateTime doesn't exist.
            // Shift forward by the gap duration so the meeting still happens
            // "at approximately the right time" rather than being silently
            // moved to a different wall-clock time by atZone's default behavior.
            //
            // Example: 02:30 in a 02:00→03:00 gap → 03:30 (shifted by 1h).
            // This matches Google Calendar / Outlook behavior.
            Duration gapDuration = transition.getDuration();
            LocalDateTime adjusted = localDateTime.plus(gapDuration);
            return adjusted.atZone(zoneId).toInstant();
        }

        // OVERLAP (fall-back): localDateTime is ambiguous — two valid offsets.
        // Pick the EARLIER offset (= the one BEFORE the clocks roll back),
        // which gives the FIRST wall-clock occurrence of this time.
        //
        // Why first, not second? Because:
        //   - It's the earlier UTC instant, so the meeting fires sooner
        //   - It matches Google/Outlook behavior
        //   - It avoids the risk of generating two instances for one recurrence
        ZoneOffset earlierOffset = transition.getOffsetBefore();
        return localDateTime.atOffset(earlierOffset).toInstant();
    }

    /**
     * Convert a UTC instant back to the local wall-clock time in a timezone.
     * This direction is always unambiguous — every Instant maps to exactly
     * one local time in any zone.
     */
    public LocalDateTime toLocal(Instant utcInstant, ZoneId zoneId) {
        return utcInstant.atZone(zoneId).toLocalDateTime();
    }
}
