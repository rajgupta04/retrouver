package com.booking.recurrence;

import com.booking.domain.RecurrenceRule;
import java.time.LocalDate;
import java.util.List;

// One implementation per Frequency (Daily/Weekly/Monthly). This is the seam
// that makes "add YEARLY support live" a one-new-class change instead of a
// rewrite — the caller (BookingService) never knows which strategy it's
// talking to, only that it can call expand(...).
public interface RecurrenceExpander {
    // dtstart comes from the Meeting, NOT from the rule — see the note in
    // RecurrenceRule.java on why dtstart lives outside the rule.
    List<LocalDate> expand(RecurrenceRule rule, LocalDate dtstart, LocalDate horizonEnd);
}