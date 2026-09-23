package com.booking.domain;

import java.util.UUID;

public class Attendee {

    public enum RsvpStatus {
        PENDING,
        ACCEPTED,
        DECLINED,
        TENTATIVE
    }

    private final UUID id;
    private final UUID meetingId;
    private final UUID userId;
    private RsvpStatus rsvpStatus;

    public Attendee(UUID id, UUID meetingId, UUID userId) {
        this(id, meetingId, userId, RsvpStatus.PENDING);
    }

    public Attendee(UUID id, UUID meetingId, UUID userId, RsvpStatus rsvpStatus) {
        this.id = id;
        this.meetingId = meetingId;
        this.userId = userId;
        this.rsvpStatus = rsvpStatus != null ? rsvpStatus : RsvpStatus.PENDING;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMeetingId() {
        return meetingId;
    }

    public UUID getUserId() {
        return userId;
    }

    public RsvpStatus getRsvpStatus() {
        return rsvpStatus;
    }

    public void setRsvpStatus(RsvpStatus rsvpStatus) {
        this.rsvpStatus = rsvpStatus;
    }
}
