package com.booking.domain;

import java.time.LocalDate;
import java.util.UUID;

public class RecurrenceRule {
    private final UUID id;

    // Deliberately NO dtstart field here. dtstart lives on Meeting.
    // Why: RFC 5545 (iCalendar — what Outlook/Google both build on) treats
    // DTSTART and RRULE as separate, peer properties, not nested. Keeping the
    // pattern date-agnostic means splitting a series (THIS_AND_FUTURE edit)
    // is just "new Meeting with a new dtstart + a shallow-copied rule" —
    // no need to rewrite anything inside the rule itself.
    private Frequency freq;

    // Every Nth occurrence — e.g. "every 2 weeks" is freq=WEEKLY, intervalN=2.
    private int intervalN = 1;

    // Only meaningful for WEEKLY (and MONTHLY with BYSETPOS).
    // e.g. ["MO", "WE", "FR"]
    private String[] byDay;

    // Only meaningful for MONTHLY, "15th of every month" style.
    // Mutually exclusive with byDay+bySetPos in practice — a rule uses one
    // monthly strategy or the other, not both.
    private Integer byMonthDay;

    // Only meaningful for MONTHLY + byDay together, e.g. "2nd Wednesday".
    // -1 means "last" (handles "last Friday of the month" cleanly without a
    // special case — negative indexing into the matching-day list).
    private Integer bySetPos;

    // Nullable — series either has an end date (until) or a max occurrence
    // count (count), or neither (runs to the horizon). Both being null means
    // "repeats forever, bounded only by however far the caller expands."
    private LocalDate until;
    private Integer count;

    public RecurrenceRule(UUID id, Frequency freq) {
        this.id = id;
        this.freq = freq;
    }

    public UUID getId() { return id; }
    public Frequency getFreq() { return freq; }
    public int getIntervalN() { return intervalN; }
    public void setIntervalN(int intervalN) { this.intervalN = intervalN; }
    public String[] getByDay() { return byDay; }
    public void setByDay(String[] byDay) { this.byDay = byDay; }
    public Integer getByMonthDay() { return byMonthDay; }
    public void setByMonthDay(Integer byMonthDay) { this.byMonthDay = byMonthDay; }
    public Integer getBySetPos() { return bySetPos; }
    public void setBySetPos(Integer bySetPos) { this.bySetPos = bySetPos; }
    public LocalDate getUntil() { return until; }
    public void setUntil(LocalDate until) { this.until = until; }
    public Integer getCount() { return count; }
    public void setCount(Integer count) { this.count = count; }

    public enum Frequency { DAILY, WEEKLY, MONTHLY }
}