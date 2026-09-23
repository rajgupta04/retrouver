package com.booking.domain;

import java.time.Instant;
import java.util.UUID;

public class Meeting {
    private final UUID id;
    private UUID roomId;
    private UUID organizerId;

    // Stored in UTC — this is what gets compared/indexed for conflict checks.
    // Never store local time directly; local time is only ever *derived* from
    // this + timezoneId, on demand.
    private Instant startUtc;
    private Instant endUtc;

    // IANA zone id, e.g. "Asia/Kolkata". This is what lets us recompute the
    // correct local wall-clock time even after a DST rule changes — the UTC
    // instant alone can't tell you that.
    private String timezoneId;

    private String title;

    // Nullable: a one-off meeting has no rule at all.
    private UUID recurrenceRuleId;

    // Nullable: only set on the *new* series created by a THIS_AND_FUTURE split,
    // pointing back to the original series it was split from. Purely for
    // audit/history — not used in any expansion or conflict logic.
    private UUID parentMeetingId;

    private MeetingStatus status;

    // Optimistic lock. Every update does
    // UPDATE ... WHERE id = ? AND version = ?
    // and increments it. A zero-row update means someone else changed this
    // meeting first — the caller must reload and retry. This is what actually
    // prevents two concurrent bookings from both passing a conflict check and
    // both writing; the transaction alone does not stop that race.
    private int version;

    private Instant createdAt;
    private Instant updatedAt;

    public Meeting(UUID id, UUID roomId, UUID organizerId, Instant startUtc,
                    Instant endUtc, String timezoneId, String title) {
        this.id = id;
        this.roomId = roomId;
        this.organizerId = organizerId;
        this.startUtc = startUtc;
        this.endUtc = endUtc;
        this.timezoneId = timezoneId;
        this.title = title;
        this.status = MeetingStatus.ACTIVE;
        this.version = 0;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    // Getters/setters — write these yourself, standard boilerplate.
    // The only one worth pausing on: any setter that changes startUtc/endUtc
    // should also bump updatedAt. Decide now whether that belongs here on the
    // entity, or in the service layer that calls it — pick one and be able to
    // say why. (My take: keep entities dumb, do it in BookingService — keeps
    // Meeting a plain data holder with no business-logic side effects hidden
    // inside a setter.)

    public UUID getId() { return id; }
    public UUID getRoomId() { return roomId; }
    public Instant getStartUtc() { return startUtc; }
    public Instant getEndUtc() { return endUtc; }
    public String getTimezoneId() { return timezoneId; }
    public UUID getRecurrenceRuleId() { return recurrenceRuleId; }
    public void setRecurrenceRuleId(UUID recurrenceRuleId) { this.recurrenceRuleId = recurrenceRuleId; }
    public MeetingStatus getStatus() { return status; }
    public void setStatus(MeetingStatus status) { this.status = status; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public enum MeetingStatus { ACTIVE, CANCELLED }
}