package com.booking.repository;

import com.booking.domain.Attendee;
import com.booking.domain.Attendee.RsvpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class AttendeeRepository {

    private final JdbcTemplate jdbc;

    public AttendeeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<Attendee> rowMapper = (rs, rowNum) -> new Attendee(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("meeting_id")),
            UUID.fromString(rs.getString("user_id")),
            RsvpStatus.valueOf(rs.getString("rsvp_status"))
    );

    public void save(Attendee attendee) {
        jdbc.update("""
            INSERT INTO attendee (id, meeting_id, user_id, rsvp_status)
            VALUES (?, ?, ?, ?)
        """,
                attendee.getId().toString(),
                attendee.getMeetingId().toString(),
                attendee.getUserId().toString(),
                attendee.getRsvpStatus().name()
        );
    }

    public void saveAll(List<Attendee> attendees) {
        for (Attendee attendee : attendees) {
            save(attendee);
        }
    }

    public List<Attendee> findByMeetingId(UUID meetingId) {
        return jdbc.query("SELECT * FROM attendee WHERE meeting_id = ?",
                rowMapper, meetingId.toString());
    }

    public void deleteByMeetingId(UUID meetingId) {
        jdbc.update("DELETE FROM attendee WHERE meeting_id = ?", meetingId.toString());
    }
}
