package com.booking.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Creates SQLite tables on application startup if they don't exist.
 * Using IF NOT EXISTS so it's safe to run every time — no migration
 * tool needed for a single-schema project.
 *
 * Column choices match the ER diagram exactly. Notable decisions:
 * - UUIDs stored as TEXT (SQLite has no native UUID type)
 * - Instants stored as TEXT in ISO-8601 format (SQLite has no datetime type,
 *   TEXT sorts lexicographically which matches ISO-8601 chronological order)
 * - version column on meeting for optimistic locking
 * - UNIQUE(master_meeting_id, original_occurrence_date) on meeting_exception
 *   to prevent two conflicting edits on the same occurrence
 * - CHECK constraints on enum-like TEXT columns as defense-in-depth
 * - DEFAULT (datetime('now')) on timestamps — SQLite-native default
 */
@Component
public class DatabaseInitializer {

    private final JdbcTemplate jdbc;

    public DatabaseInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initialize() {
        // Enable WAL mode for better concurrent read performance with SQLite
        jdbc.execute("PRAGMA journal_mode=WAL");
        // Enable foreign key enforcement (SQLite has it OFF by default)
        jdbc.execute("PRAGMA foreign_keys=ON");

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS user (
                id            TEXT PRIMARY KEY,
                name          TEXT NOT NULL,
                email         TEXT NOT NULL UNIQUE,
                timezone      TEXT NOT NULL,
                password_hash TEXT NOT NULL,
                created_at    TEXT NOT NULL DEFAULT (datetime('now'))
            )
        """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS room (
                id       TEXT PRIMARY KEY,
                name     TEXT NOT NULL,
                capacity INTEGER NOT NULL,
                active   INTEGER NOT NULL DEFAULT 1
            )
        """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS recurrence_rule (
                id           TEXT PRIMARY KEY,
                freq         TEXT NOT NULL CHECK (freq IN ('DAILY','WEEKLY','MONTHLY')),
                interval_n   INTEGER NOT NULL DEFAULT 1,
                by_day       TEXT,
                by_month_day INTEGER,
                by_set_pos   INTEGER,
                until        TEXT,
                count        INTEGER
            )
        """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS meeting (
                id                 TEXT PRIMARY KEY,
                room_id            TEXT NOT NULL REFERENCES room(id) ON DELETE RESTRICT,
                organizer_id       TEXT NOT NULL REFERENCES user(id) ON DELETE RESTRICT,
                start_utc          TEXT NOT NULL,
                end_utc            TEXT NOT NULL,
                timezone_id        TEXT NOT NULL,
                title              TEXT NOT NULL,
                recurrence_rule_id TEXT REFERENCES recurrence_rule(id) ON DELETE SET NULL,
                parent_meeting_id  TEXT REFERENCES meeting(id) ON DELETE SET NULL,
                status             TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','CANCELLED')),
                version            INTEGER NOT NULL DEFAULT 0,
                created_at         TEXT NOT NULL DEFAULT (datetime('now')),
                updated_at         TEXT NOT NULL DEFAULT (datetime('now'))
            )
        """);

        // Index on (room_id, start_utc) — this is what makes conflict queries
        // fast. The ConflictChecker does binary search in-memory, but if we
        // ever fall back to DB-level conflict checking, this index is ready.
        jdbc.execute("""
            CREATE INDEX IF NOT EXISTS idx_meeting_room_start
            ON meeting(room_id, start_utc)
        """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS meeting_exception (
                id                       TEXT PRIMARY KEY,
                master_meeting_id        TEXT NOT NULL REFERENCES meeting(id) ON DELETE CASCADE,
                original_occurrence_date TEXT NOT NULL,
                new_start_utc            TEXT,
                new_end_utc              TEXT,
                type                     TEXT NOT NULL CHECK (type IN ('MODIFIED','CANCELLED')),
                UNIQUE (master_meeting_id, original_occurrence_date)
            )
        """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS attendee (
                id          TEXT PRIMARY KEY,
                meeting_id  TEXT NOT NULL REFERENCES meeting(id) ON DELETE CASCADE,
                user_id     TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,
                rsvp_status TEXT NOT NULL DEFAULT 'PENDING' CHECK (rsvp_status IN ('PENDING','ACCEPTED','DECLINED','TENTATIVE')),
                UNIQUE (meeting_id, user_id)
            )
        """);

        // Seed default test rooms with standard UUIDs
        jdbc.execute("""
            INSERT OR IGNORE INTO room (id, name, capacity, active)
            VALUES ('11111111-1111-1111-1111-111111111111', 'Conference Room A', 10, 1)
        """);
        jdbc.execute("""
            INSERT OR IGNORE INTO room (id, name, capacity, active)
            VALUES ('22222222-2222-2222-2222-222222222222', 'Meeting Room B', 6, 1)
        """);
    }
}
