package com.booking.recurrence;

import com.booking.domain.RecurrenceRule;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class DailyStrategy implements RecurrenceExpander {

    @Override
    public List<LocalDate> expand(RecurrenceRule rule, LocalDate dtstart, LocalDate horizonEnd) {
        List<LocalDate> result = new ArrayList<>();
        LocalDate cursor = dtstart;
        int occurrenceCount = 0;

        while (!cursor.isAfter(horizonEnd)) {
            // COUNT takes priority as a stop condition if set — check it
            // before adding, so we never emit one occurrence too many.
            if (rule.getCount() != null && occurrenceCount >= rule.getCount()) break;

            // UNTIL is the other possible stop condition — both can coexist
            // with the horizon; whichever is tightest wins naturally because
            // all three are checked.
            if (rule.getUntil() != null && cursor.isAfter(rule.getUntil())) break;

            result.add(cursor);
            occurrenceCount++;

            // Jump straight by intervalN days — no need to check "does this
            // day match" day-by-day like Weekly/Monthly do, because DAILY
            // has no day-of-week or day-of-month filter to apply.
            cursor = cursor.plusDays(rule.getIntervalN());
        }
        return result;
    }
}