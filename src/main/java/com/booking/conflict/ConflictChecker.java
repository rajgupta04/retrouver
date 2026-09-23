package com.booking.conflict;

import java.time.Instant;
import java.util.*;

/**
 * Checks whether a proposed booking [newStart, newEnd) conflicts with any
 * existing booking in the same room.
 *
 * Structure: one sorted list of Interval(start, end) per room, ordered by
 * start time. Existing intervals are non-overlapping (enforced by the fact
 * that every interval passed conflict-checking before insertion).
 *
 * Conflict check: binary-search for the insertion point of newStart in the
 * sorted list, then only check the immediate neighbors — O(log n) per check,
 * not O(n). This is enough because we only need point-insertion checks, not
 * arbitrary range queries (which would need an interval tree).
 *
 * Overlap rule (half-open): [s1,e1) and [s2,e2) overlap iff s1 < e2 AND s2 < e1.
 * Half-open means a meeting ending at 11:00 and one starting at 11:00 do NOT
 * conflict — the end time is exclusive.
 */
public class ConflictChecker {

    // Per-room sorted list of booked intervals. TreeMap would also work,
    // but a plain ArrayList + Collections.binarySearch is more transparent
    // for interview explanation and sufficient for the access pattern.
    private final Map<UUID, List<Interval>> roomIndex = new HashMap<>();

    /**
     * Check if a proposed [newStart, newEnd) conflicts with anything
     * already booked in this room.
     *
     * @return true if there IS a conflict (booking should be rejected)
     */
    public boolean hasConflict(UUID roomId, Instant newStart, Instant newEnd) {
        List<Interval> intervals = roomIndex.get(roomId);
        if (intervals == null || intervals.isEmpty()) return false;

        // Binary search by start time. Collections.binarySearch returns:
        //   >= 0  → exact match at that index
        //   < 0   → -(insertion point) - 1
        int pos = Collections.binarySearch(intervals, new Interval(newStart, newEnd));
        int insertionPoint = pos >= 0 ? pos : -(pos + 1);

        // Only need to check the interval immediately BEFORE and AT the
        // insertion point. Because intervals are sorted and non-overlapping,
        // anything further away can't possibly reach [newStart, newEnd).

        // Check the interval before the insertion point (if it exists).
        // Its start <= newStart, so it can only conflict if its end > newStart.
        if (insertionPoint > 0) {
            Interval before = intervals.get(insertionPoint - 1);
            if (overlaps(before.start, before.end, newStart, newEnd)) return true;
        }

        // Check the interval at the insertion point (if it exists).
        // Its start >= newStart, so it can only conflict if newEnd > its start.
        if (insertionPoint < intervals.size()) {
            Interval at = intervals.get(insertionPoint);
            if (overlaps(at.start, at.end, newStart, newEnd)) return true;
        }

        return false;
    }

    /**
     * Batch conflict check for a recurring series. Fetches the room's index
     * once and checks all occurrences against it — avoids per-occurrence
     * overhead.
     *
     * @return list of occurrence start times that conflict (empty = all clear)
     */
    public List<Instant> findConflicts(UUID roomId, List<Interval> proposedOccurrences) {
        List<Instant> conflicts = new ArrayList<>();
        for (Interval proposed : proposedOccurrences) {
            if (hasConflict(roomId, proposed.start, proposed.end)) {
                conflicts.add(proposed.start);
            }
        }
        return conflicts;
    }

    /**
     * Register a confirmed booking into the room's index. Must only be called
     * AFTER conflict-checking passes — the sorted-list invariant (no overlaps)
     * depends on this.
     */
    public void addBooking(UUID roomId, Instant start, Instant end) {
        List<Interval> intervals = roomIndex.computeIfAbsent(roomId, k -> new ArrayList<>());
        Interval newInterval = new Interval(start, end);

        int pos = Collections.binarySearch(intervals, newInterval);
        int insertionPoint = pos >= 0 ? pos : -(pos + 1);

        // Insert at the correct sorted position — O(n) for the shift, but
        // insertions are infrequent relative to reads. If this became a
        // bottleneck, switching to a TreeSet or skip list would fix it
        // without changing the public API.
        intervals.add(insertionPoint, newInterval);
    }

    /**
     * Remove a booking from the index (needed for cancellation and series edits).
     */
    public void removeBooking(UUID roomId, Instant start, Instant end) {
        List<Interval> intervals = roomIndex.get(roomId);
        if (intervals == null) return;

        Interval target = new Interval(start, end);
        int pos = Collections.binarySearch(intervals, target);
        if (pos >= 0) {
            intervals.remove(pos);
        }
    }

    /**
     * Load existing bookings for a room (e.g. from the DB on startup).
     * The provided list must already be sorted by start time.
     */
    public void loadRoom(UUID roomId, List<Interval> sortedIntervals) {
        roomIndex.put(roomId, new ArrayList<>(sortedIntervals));
    }

    // --- Half-open interval overlap ---
    // [s1,e1) and [s2,e2) overlap iff s1 < e2 AND s2 < e1
    private boolean overlaps(Instant s1, Instant e1, Instant s2, Instant e2) {
        return s1.isBefore(e2) && s2.isBefore(e1);
    }

    /**
     * A time interval [start, end) — half-open, end is exclusive.
     * Comparable by start time for binary search.
     */
    public static class Interval implements Comparable<Interval> {
        public final Instant start;
        public final Instant end;

        public Interval(Instant start, Instant end) {
            this.start = start;
            this.end = end;
        }

        @Override
        public int compareTo(Interval other) {
            return this.start.compareTo(other.start);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Interval other)) return false;
            return start.equals(other.start) && end.equals(other.end);
        }

        @Override
        public int hashCode() {
            return Objects.hash(start, end);
        }
    }
}
