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
            CREATE TABLE IF NOT EXISTS users (
                id          TEXT PRIMARY KEY,
                name        TEXT NOT NULL,
                email       TEXT NOT NULL UNIQUE,
                timezone    TEXT NOT NULL,
                password_hash TEXT NOT NULL,
                created_at  TEXT NOT NULL
            )
        """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS rooms (
                id       TEXT PRIMARY KEY,
                name     TEXT NOT NULL,
                capacity INTEGER NOT NULL,
                active   INTEGER NOT NULL DEFAULT 1
            )
        """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS recurrence_rules (
                id           TEXT PRIMARY KEY,
                freq         TEXT NOT NULL,
                interval_n   INTEGER NOT NULL DEFAULT 1,
                by_day       TEXT,
                by_month_day INTEGER,
                by_set_pos   INTEGER,
                until_date   TEXT,
                count        INTEGER
            )
        """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS meetings (
                id                 TEXT PRIMARY KEY,
                room_id            TEXT NOT NULL,
                organizer_id       TEXT NOT NULL,
                start_utc          TEXT NOT NULL,
                end_utc            TEXT NOT NULL,
                timezone_id        TEXT NOT NULL,
                title              TEXT NOT NULL,
                recurrence_rule_id TEXT,
                parent_meeting_id  TEXT,
                status             TEXT NOT NULL DEFAULT 'ACTIVE',
                version            INTEGER NOT NULL DEFAULT 0,
                created_at         TEXT NOT NULL,
                updated_at         TEXT NOT NULL,
                FOREIGN KEY (room_id) REFERENCES rooms(id) ON DELETE RESTRICT,
                FOREIGN KEY (organizer_id) REFERENCES users(id),
                FOREIGN KEY (recurrence_rule_id) REFERENCES recurrence_rules(id),
                FOREIGN KEY (parent_meeting_id) REFERENCES meetings(id)
            )
        """);

        // Index on (room_id, start_utc) — this is what makes conflict queries
        // fast. The ConflictChecker does binary search in-memory, but if we
        // ever fall back to DB-level conflict checking, this index is ready.
        jdbc.execute("""
            CREATE INDEX IF NOT EXISTS idx_meetings_room_start
            ON meetings(room_id, start_utc)
        """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS meeting_exceptions (
                id                       TEXT PRIMARY KEY,
                master_meeting_id        TEXT NOT NULL,
                original_occurrence_date TEXT NOT NULL,
                new_start_utc            TEXT,
                new_end_utc              TEXT,
                type                     TEXT NOT NULL,
                FOREIGN KEY (master_meeting_id) REFERENCES meetings(id) ON DELETE CASCADE,
                UNIQUE(master_meeting_id, original_occurrence_date)
            )
        """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS attendees (
                id          TEXT PRIMARY KEY,
                meeting_id  TEXT NOT NULL,
                user_id     TEXT NOT NULL,
                rsvp_status TEXT NOT NULL DEFAULT 'PENDING',
                FOREIGN KEY (meeting_id) REFERENCES meetings(id) ON DELETE CASCADE,
                FOREIGN KEY (user_id) REFERENCES users(id),
                UNIQUE(meeting_id, user_id)
            )
        """);
    }
}
