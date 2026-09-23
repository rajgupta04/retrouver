package com.booking.domain;

import java.time.Instant;
import java.util.UUID;

public class User {
    private final UUID id;
    private String name;

    // UNIQUE at the DB level too (see the DDL — UNIQUE on email). Doing it
    // in both places is deliberate: the DB constraint is the real guarantee
    // (survives any application bug), an app-level check before insert is
    // just a nicer error message before you hit the DB round-trip.
    private String email;

    // Each user's own IANA timezone — used as the DEFAULT timezone when
    // they organize a meeting, but NOT what's stored on the Meeting itself.
    // Meeting always stores its own timezoneId, because a meeting's
    // "correct" timezone is wherever the organizer set it at booking time,
    // not wherever the organizer happens to live now — those can diverge
    // (e.g. organizer relocates after scheduling a recurring series).
    private String timezone;

    // Never store or return plaintext. In the persistence layer, hash with
    // something like BCrypt before writing — this field only ever holds
    // the hash, never the raw password, even transiently.
    private String passwordHash;

    private Instant createdAt;

    public User(UUID id, String name, String email, String timezone, String passwordHash) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.timezone = timezone;
        this.passwordHash = passwordHash;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getTimezone() { return timezone; }
    public String getPasswordHash() { return passwordHash; }
    public Instant getCreatedAt() { return createdAt; }
}