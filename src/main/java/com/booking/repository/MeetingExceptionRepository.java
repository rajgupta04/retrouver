package com.booking.repository;

import com.booking.domain.MeetingException;
import com.booking.domain.MeetingException.ExceptionType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public class MeetingExceptionRepository {

    private final JdbcTemplate jdbc;

    public MeetingExceptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<MeetingException> rowMapper = (rs, rowNum) -> {
        MeetingException ex = new MeetingException(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("master_meeting_id")),
                LocalDate.parse(rs.getString("original_occurrence_date")),
                ExceptionType.valueOf(rs.getString("type"))
        );
        String newStart = rs.getString("new_start_utc");
        if (newStart != null) ex.setNewStartUtc(Instant.parse(newStart));
        String newEnd = rs.getString("new_end_utc");
        if (newEnd != null) ex.setNewEndUtc(Instant.parse(newEnd));
        return ex;
    };

    public void save(MeetingException ex) {
        jdbc.update("""
            INSERT INTO meeting_exceptions (id, master_meeting_id,
                original_occurrence_date, new_start_utc, new_end_utc, type)
            VALUES (?, ?, ?, ?, ?, ?)
        """,
                ex.getId().toString(),
                ex.getMasterMeetingId().toString(),
                ex.getOriginalOccurrenceDate().toString(),
                ex.getNewStartUtc() != null ? ex.getNewStartUtc().toString() : null,
                ex.getNewEndUtc() != null ? ex.getNewEndUtc().toString() : null,
                ex.getType().name()
        );
    }

    /**
     * All exceptions for a master meeting — needed when expanding a recurring
     * series to filter out CANCELLED occurrences and apply MODIFIED times.
     */
    public List<MeetingException> findByMasterMeetingId(UUID masterMeetingId) {
        return jdbc.query(
                "SELECT * FROM meeting_exceptions WHERE master_meeting_id = ?",
                rowMapper, masterMeetingId.toString()
        );
    }

    /**
     * Find exceptions on or after a date — used during THIS_AND_FUTURE split
     * to move exceptions from the old series to the new one.
     */
    public List<MeetingException> findByMasterIdFromDate(UUID masterMeetingId, LocalDate fromDate) {
        return jdbc.query(
                "SELECT * FROM meeting_exceptions WHERE master_meeting_id = ? AND original_occurrence_date >= ?",
                rowMapper, masterMeetingId.toString(), fromDate.toString()
        );
    }

    public void deleteByMasterMeetingId(UUID masterMeetingId) {
        jdbc.update("DELETE FROM meeting_exceptions WHERE master_meeting_id = ?",
                masterMeetingId.toString());
    }
}
