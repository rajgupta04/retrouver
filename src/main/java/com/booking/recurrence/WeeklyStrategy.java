package com.booking.recurrence;

import com.booking.domain.RecurrenceRule;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class WeeklyStrategy implements RecurrenceExpander {

    // Maps "MO" -> MONDAY etc. Keep this as a small local helper — no need
    // for a library for a 7-entry lookup.
    private DayOfWeek parseDay(String code) {
        return switch (code) {
            case "MO" -> DayOfWeek.MONDAY;
            case "TU" -> DayOfWeek.TUESDAY;
            case "WE" -> DayOfWeek.WEDNESDAY;
            case "TH" -> DayOfWeek.THURSDAY;
            case "FR" -> DayOfWeek.FRIDAY;
            case "SA" -> DayOfWeek.SATURDAY;
            case "SU" -> DayOfWeek.SUNDAY;
            default -> throw new IllegalArgumentException("Unknown day code: " + code);
        };
    }

    @Override
    public List<LocalDate> expand(RecurrenceRule rule, LocalDate dtstart, LocalDate horizonEnd) {
        List<LocalDate> result = new ArrayList<>();

        Set<DayOfWeek> targetDays = Arrays.stream(rule.getByDay())
                .map(this::parseDay)
                .collect(Collectors.toSet());

        // Anchor the "every N weeks" counting to the week dtstart falls in —
        // otherwise "every 2 weeks" has no fixed reference point to count
        // from, and you can't tell which weeks are "on" vs "off".
        LocalDate anchorWeekStart = dtstart.with(DayOfWeek.MONDAY);

        LocalDate cursor = dtstart;
        int occurrenceCount = 0;

        while (!cursor.isAfter(horizonEnd)) {
            if (rule.getCount() != null && occurrenceCount >= rule.getCount()) break;
            if (rule.getUntil() != null && cursor.isAfter(rule.getUntil())) break;

            LocalDate thisWeekStart = cursor.with(DayOfWeek.MONDAY);
            long weeksSinceAnchor = java.time.temporal.ChronoUnit.WEEKS.between(anchorWeekStart, thisWeekStart);

            // Only an "active" week (matches the N-week interval) AND a
            // matching weekday counts as an occurrence.
            boolean isActiveWeek = (weeksSinceAnchor % rule.getIntervalN()) == 0;
            boolean isMatchingDay = targetDays.contains(cursor.getDayOfWeek());

            if (isActiveWeek && isMatchingDay && !cursor.isBefore(dtstart)) {
                result.add(cursor);
                occurrenceCount++;
            }

            cursor = cursor.plusDays(1);
        }
        return result;
    }
}