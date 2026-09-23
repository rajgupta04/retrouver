package com.booking.recurrence;

import com.booking.domain.RecurrenceRule;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

public class MonthlyStrategy implements RecurrenceExpander {

    @Override
    public List<LocalDate> expand(RecurrenceRule rule, LocalDate dtstart, LocalDate horizonEnd) {
        List<LocalDate> result = new ArrayList<>();
        YearMonth cursorMonth = YearMonth.from(dtstart);
        YearMonth horizonMonth = YearMonth.from(horizonEnd);
        int occurrenceCount = 0;

        while (!cursorMonth.isAfter(horizonMonth)) {
            if (rule.getCount() != null && occurrenceCount >= rule.getCount()) break;

            LocalDate occurrenceThisMonth = rule.getByMonthDay() != null
                    ? resolveByMonthDay(rule, cursorMonth)
                    : resolveByDaySetPos(rule, cursorMonth);

            // resolveByMonthDay returns null when the day doesn't exist that
            // month (e.g. BYMONTHDAY=31 in a 30-day month) — per RFC 5545,
            // the correct behavior is to SKIP that month entirely, not clamp
            // to the last valid day. Clamping silently produces a wrong date
            // that drifts the pattern 
            if (occurrenceThisMonth != null
                    && !occurrenceThisMonth.isBefore(dtstart)
                    && !occurrenceThisMonth.isAfter(horizonEnd)
                    && (rule.getUntil() == null || !occurrenceThisMonth.isAfter(rule.getUntil()))) {
                result.add(occurrenceThisMonth);
                occurrenceCount++;
            }

            cursorMonth = cursorMonth.plusMonths(rule.getIntervalN());
        }
        return result;
    }

    // "15th of every month" case.
    private LocalDate resolveByMonthDay(RecurrenceRule rule, YearMonth month) {
        int day = rule.getByMonthDay();
        if (day > month.lengthOfMonth()) return null; // skip, don't clamp
        return month.atDay(day);
    }

    // "2nd Wednesday" / "last Friday" case — computed directly, not by
    // looping every day of the month (O(1) per month instead of O(days)).
    private LocalDate resolveByDaySetPos(RecurrenceRule rule, YearMonth month) {
        DayOfWeek targetDay = parseDay(rule.getByDay()[0]); // monthly BYDAY is a single day
        int setPos = rule.getBySetPos();

        List<LocalDate> matchingDaysInMonth = new ArrayList<>();
        LocalDate d = month.atDay(1);
        while (YearMonth.from(d).equals(month)) {
            if (d.getDayOfWeek() == targetDay) matchingDaysInMonth.add(d);
            d = d.plusDays(1);
        }

        // setPos=1 -> first match (index 0). setPos=-1 -> last match
        // (matchingDaysInMonth.size() - 1). Negative indexing this way
        // handles "last X" without a separate code path.
        int index = setPos > 0 ? setPos - 1 : matchingDaysInMonth.size() + setPos;
        if (index < 0 || index >= matchingDaysInMonth.size()) return null;
        return matchingDaysInMonth.get(index);
    }

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
}