package com.booking.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public class MeetingException {
    private final UUID id;
    private final UUID masterMeetingId;

    // Which single occurrence this overrides — matched against a date
    // produced by RecurrenceExpander, not a full datetime. One exception
    // per occurrence date per series (enforced by a DB unique constraint on
    // (masterMeetingId, originalOccurrenceDate) — prevents two conflicting
    // edits landing on the same occurrence).
    private final LocalDate originalOccurrenceDate;

    // Only set when type == MODIFIED. Null for CANCELLED — a cancelled
    // occurrence has no new time, it's just removed from the expansion.
    private Instant newStartUtc;
    private Instant newEndUtc;

    private ExceptionType type;

    public MeetingException(UUID id, UUID masterMeetingId,LocalDate originalOccurrenceDate, ExceptionType type) {
        this.id = id;
        this.masterMeetingId = masterMeetingId;
        this.originalOccurrenceDate = originalOccurrenceDate;
        this.type = type;
    }

    public UUID getMasterMeetingId() { return masterMeetingId; }
    public LocalDate getOriginalOccurrenceDate() { return originalOccurrenceDate; }
    public Instant getNewStartUtc() { return newStartUtc; }
    public void setNewStartUtc(Instant newStartUtc) { this.newStartUtc = newStartUtc; }
    public Instant getNewEndUtc() { return newEndUtc; }
    public void setNewEndUtc(Instant newEndUtc) { this.newEndUtc = newEndUtc; }
    public ExceptionType getType() { return type; }

    public enum ExceptionType { MODIFIED, CANCELLED }
}