package com.booking.repository;

import com.booking.domain.Meeting;
import com.booking.domain.Meeting.MeetingStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MeetingRepository {

    private final JdbcTemplate jdbc;

    public MeetingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<Meeting> rowMapper = (rs, rowNum) -> {
        Meeting m = new Meeting(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("room_id")),
                UUID.fromString(rs.getString("organizer_id")),
                Instant.parse(rs.getString("start_utc")),
                Instant.parse(rs.getString("end_utc")),
                rs.getString("timezone_id"),
                rs.getString("title")
        );
        String ruleId = rs.getString("recurrence_rule_id");
        if (ruleId != null) m.setRecurrenceRuleId(UUID.fromString(ruleId));
        m.setStatus(MeetingStatus.valueOf(rs.getString("status")));
        m.setVersion(rs.getInt("version"));
        return m;
    };

    public void save(Meeting m) {
        jdbc.update("""
            INSERT INTO meeting (id, room_id, organizer_id, start_utc, end_utc,
                timezone_id, title, recurrence_rule_id, parent_meeting_id,
                status, version, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
                m.getId().toString(),
                m.getRoomId().toString(),
                m.getOrganizerId().toString(),
                m.getStartUtc().toString(),
                m.getEndUtc().toString(),
                m.getTimezoneId(),
                m.getTitle(),
                m.getRecurrenceRuleId() != null ? m.getRecurrenceRuleId().toString() : null,
                m.getParentMeetingId() != null ? m.getParentMeetingId().toString() : null,
                m.getStatus().name(),
                m.getVersion(),
                Instant.now().toString(),
                Instant.now().toString()
        );
    }

    public Optional<Meeting> findById(UUID id) {
        List<Meeting> results = jdbc.query(
                "SELECT * FROM meeting WHERE id = ?",
                rowMapper, id.toString()
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Find all active meetings in a room, ordered by start time.
     * Used to populate ConflictChecker's in-memory index on startup.
     */
    public List<Meeting> findByRoomIdOrderByStart(UUID roomId) {
        return jdbc.query(
                "SELECT * FROM meeting WHERE room_id = ? AND status = 'ACTIVE' ORDER BY start_utc",
                rowMapper, roomId.toString()
        );
    }

    /**
     * Optimistic-lock update: WHERE version = expectedVersion.
     * Returns true if the update succeeded (row was at the expected version),
     * false if someone else modified it first (stale version → caller must
     * reload and retry).
     */
    public boolean updateWithVersion(Meeting m, int expectedVersion) {
        int rows = jdbc.update("""
            UPDATE meeting SET start_utc = ?, end_utc = ?, timezone_id = ?,
                status = ?, version = ?, updated_at = ?,
                recurrence_rule_id = ?
            WHERE id = ? AND version = ?
        """,
                m.getStartUtc().toString(),
                m.getEndUtc().toString(),
                m.getTimezoneId(),
                m.getStatus().name(),
                expectedVersion + 1,
                Instant.now().toString(),
                m.getRecurrenceRuleId() != null ? m.getRecurrenceRuleId().toString() : null,
                m.getId().toString(),
                expectedVersion
        );
        return rows > 0;
    }

    public void deleteById(UUID id) {
        jdbc.update("DELETE FROM meeting WHERE id = ?", id.toString());
    }
}
