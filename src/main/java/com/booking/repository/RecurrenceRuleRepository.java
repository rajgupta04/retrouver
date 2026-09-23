package com.booking.repository;

import com.booking.domain.RecurrenceRule;
import com.booking.domain.RecurrenceRule.Frequency;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RecurrenceRuleRepository {

    private final JdbcTemplate jdbc;

    public RecurrenceRuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<RecurrenceRule> rowMapper = (rs, rowNum) -> {
        RecurrenceRule r = new RecurrenceRule(
                UUID.fromString(rs.getString("id")),
                Frequency.valueOf(rs.getString("freq"))
        );
        r.setIntervalN(rs.getInt("interval_n"));

        String byDay = rs.getString("by_day");
        if (byDay != null) r.setByDay(byDay.split(","));

        int byMonthDay = rs.getInt("by_month_day");
        if (!rs.wasNull()) r.setByMonthDay(byMonthDay);

        int bySetPos = rs.getInt("by_set_pos");
        if (!rs.wasNull()) r.setBySetPos(bySetPos);

        String until = rs.getString("until");
        if (until != null) r.setUntil(LocalDate.parse(until));

        int count = rs.getInt("count");
        if (!rs.wasNull()) r.setCount(count);

        return r;
    };

    public void save(RecurrenceRule rule) {
        jdbc.update("""
            INSERT INTO recurrence_rule (id, freq, interval_n, by_day,
                by_month_day, by_set_pos, until, count)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
                rule.getId().toString(),
                rule.getFreq().name(),
                rule.getIntervalN(),
                rule.getByDay() != null ? String.join(",", rule.getByDay()) : null,
                rule.getByMonthDay(),
                rule.getBySetPos(),
                rule.getUntil() != null ? rule.getUntil().toString() : null,
                rule.getCount()
        );
    }

    public Optional<RecurrenceRule> findById(UUID id) {
        List<RecurrenceRule> results = jdbc.query(
                "SELECT * FROM recurrence_rule WHERE id = ?",
                rowMapper, id.toString()
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public void updateUntil(UUID id, LocalDate newUntil) {
        jdbc.update("UPDATE recurrence_rule SET until = ? WHERE id = ?",
                newUntil.toString(), id.toString());
    }
}
