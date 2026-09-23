package com.booking.conflict;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ConflictCheckerTest {

    private ConflictChecker checker;
    private UUID roomA;
    private UUID roomB;

    @BeforeEach
    void setUp() {
        checker = new ConflictChecker();
        roomA = UUID.randomUUID();
        roomB = UUID.randomUUID();
    }

    // ==================== BASIC OVERLAP DETECTION ====================

    @Test
    void noBookings_noConflict() {
        assertFalse(checker.hasConflict(roomA,
                instant("10:00"), instant("11:00")));
    }

    @Test
    void exactSameSlot_conflicts() {
        checker.addBooking(roomA, instant("10:00"), instant("11:00"));

        assertTrue(checker.hasConflict(roomA,
                instant("10:00"), instant("11:00")));
    }

    @Test
    void overlappingSlot_conflicts() {
        // Existing: [10:00, 11:00)
        checker.addBooking(roomA, instant("10:00"), instant("11:00"));

        // New: [10:30, 11:30) — starts during existing
        assertTrue(checker.hasConflict(roomA,
                instant("10:30"), instant("11:30")));
    }

    @Test
    void containedSlot_conflicts() {
        // Existing: [09:00, 12:00)
        checker.addBooking(roomA, instant("09:00"), instant("12:00"));

        // New: [10:00, 11:00) — entirely inside existing
        assertTrue(checker.hasConflict(roomA,
                instant("10:00"), instant("11:00")));
    }

    @Test
    void containingSlot_conflicts() {
        // Existing: [10:00, 11:00)
        checker.addBooking(roomA, instant("10:00"), instant("11:00"));

        // New: [09:00, 12:00) — entirely contains existing
        assertTrue(checker.hasConflict(roomA,
                instant("09:00"), instant("12:00")));
    }

    // ==================== HALF-OPEN BOUNDARY (the critical test) ====================

    @Test
    void adjacentSlots_noConflict_halfOpen() {
        // This is the key test for the half-open interval rule:
        // [10:00, 11:00) and [11:00, 12:00) should NOT conflict.
        // End time is exclusive — meeting A ends at 11:00, meeting B starts at 11:00.
        // Physically: people leave room, next group enters. Not a clash.
        checker.addBooking(roomA, instant("10:00"), instant("11:00"));

        assertFalse(checker.hasConflict(roomA,
                instant("11:00"), instant("12:00")));
    }

    @Test
    void adjacentSlots_reversed_noConflict() {
        // Same test, reversed insertion order
        checker.addBooking(roomA, instant("11:00"), instant("12:00"));

        assertFalse(checker.hasConflict(roomA,
                instant("10:00"), instant("11:00")));
    }

    // ==================== NON-OVERLAPPING ====================

    @Test
    void wellSeparatedSlots_noConflict() {
        checker.addBooking(roomA, instant("09:00"), instant("10:00"));

        assertFalse(checker.hasConflict(roomA,
                instant("14:00"), instant("15:00")));
    }

    @Test
    void beforeExisting_noConflict() {
        checker.addBooking(roomA, instant("14:00"), instant("15:00"));

        assertFalse(checker.hasConflict(roomA,
                instant("09:00"), instant("10:00")));
    }

    // ==================== MULTI-ROOM ISOLATION ====================

    @Test
    void differentRooms_noConflict() {
        // Same time slot, different rooms — should NOT conflict
        checker.addBooking(roomA, instant("10:00"), instant("11:00"));

        assertFalse(checker.hasConflict(roomB,
                instant("10:00"), instant("11:00")));
    }

    @Test
    void sameRoom_conflicts_differentRoom_doesNot() {
        checker.addBooking(roomA, instant("10:00"), instant("11:00"));

        assertTrue(checker.hasConflict(roomA,
                instant("10:30"), instant("11:30")));
        assertFalse(checker.hasConflict(roomB,
                instant("10:30"), instant("11:30")));
    }

    // ==================== MULTIPLE BOOKINGS (binary search correctness) ====================

    @Test
    void multipleBookings_conflictInMiddle() {
        // Fill a day with 3 bookings
        checker.addBooking(roomA, instant("09:00"), instant("10:00"));
        checker.addBooking(roomA, instant("11:00"), instant("12:00"));
        checker.addBooking(roomA, instant("14:00"), instant("15:00"));

        // Try to book 11:30-12:30 — overlaps the middle one
        assertTrue(checker.hasConflict(roomA,
                instant("11:30"), instant("12:30")));
    }

    @Test
    void multipleBookings_gapBetweenIsAvailable() {
        checker.addBooking(roomA, instant("09:00"), instant("10:00"));
        checker.addBooking(roomA, instant("11:00"), instant("12:00"));
        checker.addBooking(roomA, instant("14:00"), instant("15:00"));

        // 12:00-13:00 is in the gap — should be available
        assertFalse(checker.hasConflict(roomA,
                instant("12:00"), instant("13:00")));
    }

    @Test
    void multipleBookings_allAdjacentSlots_noConflicts() {
        // Back-to-back-to-back — all adjacent, none overlapping
        checker.addBooking(roomA, instant("09:00"), instant("10:00"));
        checker.addBooking(roomA, instant("10:00"), instant("11:00"));
        checker.addBooking(roomA, instant("11:00"), instant("12:00"));

        // Every boundary is clean — test the seams
        assertFalse(checker.hasConflict(roomA,
                instant("08:00"), instant("09:00"))); // before first
        assertFalse(checker.hasConflict(roomA,
                instant("12:00"), instant("13:00"))); // after last
    }

    // ==================== REMOVE BOOKING ====================

    @Test
    void removeBooking_thenSlotIsAvailable() {
        checker.addBooking(roomA, instant("10:00"), instant("11:00"));
        assertTrue(checker.hasConflict(roomA,
                instant("10:00"), instant("11:00")));

        checker.removeBooking(roomA, instant("10:00"), instant("11:00"));
        assertFalse(checker.hasConflict(roomA,
                instant("10:00"), instant("11:00")));
    }

    // ==================== BATCH CONFLICT CHECK ====================

    @Test
    void findConflicts_returnsOnlyConflictingOccurrences() {
        checker.addBooking(roomA, instant("10:00"), instant("11:00"));

        List<ConflictChecker.Interval> proposed = List.of(
                new ConflictChecker.Interval(instant("09:00"), instant("10:00")), // OK (adjacent)
                new ConflictChecker.Interval(instant("10:30"), instant("11:30")), // CONFLICT
                new ConflictChecker.Interval(instant("14:00"), instant("15:00"))  // OK
        );

        List<Instant> conflicts = checker.findConflicts(roomA, proposed);

        assertEquals(1, conflicts.size());
        assertEquals(instant("10:30"), conflicts.get(0));
    }

    @Test
    void findConflicts_emptyWhenNoConflicts() {
        List<ConflictChecker.Interval> proposed = List.of(
                new ConflictChecker.Interval(instant("09:00"), instant("10:00")),
                new ConflictChecker.Interval(instant("14:00"), instant("15:00"))
        );

        List<Instant> conflicts = checker.findConflicts(roomA, proposed);
        assertTrue(conflicts.isEmpty());
    }

    // ==================== LOAD ROOM ====================

    @Test
    void loadRoom_populatesIndex() {
        List<ConflictChecker.Interval> existing = List.of(
                new ConflictChecker.Interval(instant("09:00"), instant("10:00")),
                new ConflictChecker.Interval(instant("11:00"), instant("12:00"))
        );
        checker.loadRoom(roomA, existing);

        assertTrue(checker.hasConflict(roomA,
                instant("09:30"), instant("10:30")));
        assertFalse(checker.hasConflict(roomA,
                instant("10:00"), instant("11:00")));
    }

    // ==================== HELPER ====================

    /**
     * Shorthand: creates an Instant on 2026-01-15 at the given HH:MM UTC.
     * All tests use the same date so we're only testing time-based overlap,
     * not date arithmetic (that's RecurrenceExpander's job).
     */
    private Instant instant(String time) {
        return Instant.parse("2026-01-15T" + time + ":00Z");
    }
}
