package com.booking.timezone;

import org.junit.jupiter.api.Test;

import java.time.*;

import static org.junit.jupiter.api.Assertions.*;

class TimezoneResolverTest {

    private final TimezoneResolver resolver = new TimezoneResolver();

    // US Eastern: spring forward 2026-03-08 02:00 → 03:00
    //             fall back     2026-11-01 02:00 → 01:00
    private final ZoneId eastern = ZoneId.of("America/New_York");

    // ==================== NORMAL (no DST transition) ====================

    @Test
    void normalTime_convertsCorrectly() {
        // 2026-01-15 10:00 New York = UTC-5 = 15:00 UTC
        LocalDateTime local = LocalDateTime.of(2026, 1, 15, 10, 0);
        Instant result = resolver.toUtc(local, eastern);

        assertEquals(Instant.parse("2026-01-15T15:00:00Z"), result);
    }

    @Test
    void normalTime_summer_convertsCorrectly() {
        // 2026-07-15 10:00 New York = UTC-4 (EDT) = 14:00 UTC
        LocalDateTime local = LocalDateTime.of(2026, 7, 15, 10, 0);
        Instant result = resolver.toUtc(local, eastern);

        assertEquals(Instant.parse("2026-07-15T14:00:00Z"), result);
    }

    // ==================== SPRING FORWARD (gap) ====================

    @Test
    void springForward_gapTime_shiftsForward() {
        // 2026-03-08 02:30 doesn't exist — clocks jump 02:00 → 03:00.
        // Our resolution: shift forward by gap duration (1h) → 03:30 EDT
        // 03:30 EDT = UTC-4 = 07:30 UTC
        LocalDateTime local = LocalDateTime.of(2026, 3, 8, 2, 30);
        Instant result = resolver.toUtc(local, eastern);

        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), result);
    }

    @Test
    void springForward_exactGapStart_shiftsForward() {
        // 02:00 is the exact start of the gap
        // Shifted by 1h → 03:00 EDT = UTC-4 = 07:00 UTC
        LocalDateTime local = LocalDateTime.of(2026, 3, 8, 2, 0);
        Instant result = resolver.toUtc(local, eastern);

        assertEquals(Instant.parse("2026-03-08T07:00:00Z"), result);
    }

    @Test
    void springForward_justBeforeGap_normalConversion() {
        // 01:59 is just before the gap — normal EST conversion
        // 01:59 EST = UTC-5 = 06:59 UTC
        LocalDateTime local = LocalDateTime.of(2026, 3, 8, 1, 59);
        Instant result = resolver.toUtc(local, eastern);

        assertEquals(Instant.parse("2026-03-08T06:59:00Z"), result);
    }

    @Test
    void springForward_justAfterGap_normalConversion() {
        // 03:00 is just after the gap — normal EDT conversion
        // 03:00 EDT = UTC-4 = 07:00 UTC
        LocalDateTime local = LocalDateTime.of(2026, 3, 8, 3, 0);
        Instant result = resolver.toUtc(local, eastern);

        assertEquals(Instant.parse("2026-03-08T07:00:00Z"), result);
    }

    // ==================== FALL BACK (overlap) ====================

    @Test
    void fallBack_ambiguousTime_picksFirstOccurrence() {
        // 2026-11-01 01:30 happens TWICE:
        //   First occurrence:  01:30 EDT (UTC-4) = 05:30 UTC
        //   Second occurrence: 01:30 EST (UTC-5) = 06:30 UTC
        // Our resolution: pick the FIRST (earlier UTC), offset = -04:00
        LocalDateTime local = LocalDateTime.of(2026, 11, 1, 1, 30);
        Instant result = resolver.toUtc(local, eastern);

        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), result);
    }

    @Test
    void fallBack_exactOverlapStart_picksFirst() {
        // 01:00 is ambiguous (both EDT and EST have this time)
        // First occurrence: 01:00 EDT = UTC-4 = 05:00 UTC
        LocalDateTime local = LocalDateTime.of(2026, 11, 1, 1, 0);
        Instant result = resolver.toUtc(local, eastern);

        assertEquals(Instant.parse("2026-11-01T05:00:00Z"), result);
    }

    @Test
    void fallBack_justBeforeOverlap_normalConversion() {
        // 00:59 is unambiguous — only EDT applies
        // 00:59 EDT = UTC-4 = 04:59 UTC
        LocalDateTime local = LocalDateTime.of(2026, 11, 1, 0, 59);
        Instant result = resolver.toUtc(local, eastern);

        assertEquals(Instant.parse("2026-11-01T04:59:00Z"), result);
    }

    @Test
    void fallBack_afterOverlap_normalConversion() {
        // 02:00 is unambiguous — only EST applies
        // 02:00 EST = UTC-5 = 07:00 UTC
        LocalDateTime local = LocalDateTime.of(2026, 11, 1, 2, 0);
        Instant result = resolver.toUtc(local, eastern);

        assertEquals(Instant.parse("2026-11-01T07:00:00Z"), result);
    }

    // ==================== toLocal (always unambiguous) ====================

    @Test
    void toLocal_convertsCorrectly() {
        Instant utc = Instant.parse("2026-01-15T15:00:00Z");
        LocalDateTime local = resolver.toLocal(utc, eastern);

        assertEquals(LocalDateTime.of(2026, 1, 15, 10, 0), local);
    }

    @Test
    void toLocal_duringDst_usesCorrectOffset() {
        // 14:00 UTC during summer = 10:00 EDT (UTC-4)
        Instant utc = Instant.parse("2026-07-15T14:00:00Z");
        LocalDateTime local = resolver.toLocal(utc, eastern);

        assertEquals(LocalDateTime.of(2026, 7, 15, 10, 0), local);
    }

    // ==================== THE KEY INTERVIEW TEST ====================
    // A weekly meeting whose recurrence crosses a DST boundary.
    // The local wall-clock time stays fixed; the UTC value changes.

    @Test
    void weeklyMeetingAcrossDst_localTimeStaysFixed_utcChanges() {
        // Meeting: every Monday at 10:00 AM Eastern
        // Before DST (March 2): 10:00 EST = UTC-5 = 15:00 UTC
        // After DST  (March 9): 10:00 EDT = UTC-4 = 14:00 UTC

        LocalDateTime beforeDst = LocalDateTime.of(2026, 3, 2, 10, 0);
        LocalDateTime afterDst = LocalDateTime.of(2026, 3, 9, 10, 0);

        Instant beforeUtc = resolver.toUtc(beforeDst, eastern);
        Instant afterUtc = resolver.toUtc(afterDst, eastern);

        // Both are 10:00 local
        assertEquals(10, beforeDst.getHour());
        assertEquals(10, afterDst.getHour());

        // But different UTC times
        assertEquals(Instant.parse("2026-03-02T15:00:00Z"), beforeUtc); // UTC-5
        assertEquals(Instant.parse("2026-03-09T14:00:00Z"), afterUtc);  // UTC-4
        assertNotEquals(beforeUtc.atZone(ZoneOffset.UTC).getHour(),
                        afterUtc.atZone(ZoneOffset.UTC).getHour());
    }

    // ==================== DIFFERENT TIMEZONE ====================

    @Test
    void indiaTimezone_noGapsOrOverlaps() {
        // Asia/Kolkata has no DST — offset is always +05:30
        ZoneId india = ZoneId.of("Asia/Kolkata");
        LocalDateTime local = LocalDateTime.of(2026, 6, 15, 10, 0);
        Instant result = resolver.toUtc(local, india);

        // 10:00 IST = 04:30 UTC
        assertEquals(Instant.parse("2026-06-15T04:30:00Z"), result);
    }
}
