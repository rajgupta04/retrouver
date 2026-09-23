package com.booking.service;

import com.booking.conflict.ConflictChecker;
import com.booking.conflict.ConflictChecker.Interval;
import com.booking.domain.*;
import com.booking.domain.Meeting.MeetingStatus;
import com.booking.domain.MeetingException.ExceptionType;
import com.booking.domain.RecurrenceRule.Frequency;
import com.booking.recurrence.*;
import com.booking.repository.*;
import com.booking.timezone.TimezoneResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/**
 * Central orchestrator — the ONLY place where recurrence expansion, conflict
 * checking, timezone resolution, and persistence all meet. Every other
 * component is deliberately unaware of the others:
 *   - RecurrenceExpander produces dates (no DB, no conflict awareness)
 *   - ConflictChecker checks intervals (no recurrence awareness)
 *   - TimezoneResolver converts times (no domain awareness)
 *   - Repositories do CRUD (no business logic)
 * This separation of concerns is deliberate and interview-grade.
 */
@Service
@Transactional
public class BookingService {

    private final MeetingRepository meetingRepo;
    private final RecurrenceRuleRepository ruleRepo;
    private final MeetingExceptionRepository exceptionRepo;
    private final RoomRepository roomRepo;
    private final AttendeeRepository attendeeRepo;
    private final ConflictChecker conflictChecker;
    private final TimezoneResolver timezoneResolver;

    // Strategy lookup — one expander per Frequency type.
    // Adding YEARLY = put a new entry here + write the strategy class. Done.
    private final Map<Frequency, RecurrenceExpander> expanders;

    public BookingService(MeetingRepository meetingRepo,
                          RecurrenceRuleRepository ruleRepo,
                          MeetingExceptionRepository exceptionRepo,
                          RoomRepository roomRepo,
                          AttendeeRepository attendeeRepo,
                          ConflictChecker conflictChecker,
                          TimezoneResolver timezoneResolver) {
        this.meetingRepo = meetingRepo;
        this.ruleRepo = ruleRepo;
        this.exceptionRepo = exceptionRepo;
        this.roomRepo = roomRepo;
        this.attendeeRepo = attendeeRepo;
        this.conflictChecker = conflictChecker;
        this.timezoneResolver = timezoneResolver;

        this.expanders = Map.of(
                Frequency.DAILY, new DailyStrategy(),
                Frequency.WEEKLY, new WeeklyStrategy(),
                Frequency.MONTHLY, new MonthlyStrategy()
        );
    }

    // ========================================================================
    // BOOK SINGLE
    // ========================================================================

    public Meeting bookSingle(UUID roomId, UUID organizerId,
                              LocalDateTime localStart, LocalDateTime localEnd,
                              String timezoneId, String title) {
        return bookSingle(roomId, organizerId, List.of(), localStart, localEnd, timezoneId, title);
    }

    /**
     * Book a one-off (non-recurring) meeting with optional attendees.
     *
     * Flow: convert local→UTC → check conflicts → persist meeting & attendees → register in index.
     */
    public Meeting bookSingle(UUID roomId, UUID organizerId, List<UUID> attendeeIds,
                              LocalDateTime localStart, LocalDateTime localEnd,
                              String timezoneId, String title) {

        ZoneId zone = ZoneId.of(timezoneId);
        Instant startUtc = timezoneResolver.toUtc(localStart, zone);
        Instant endUtc = timezoneResolver.toUtc(localEnd, zone);

        if (conflictChecker.hasConflict(roomId, startUtc, endUtc)) {
            throw new BookingConflictException(
                    "Room is already booked for the requested time slot");
        }

        Meeting meeting = new Meeting(UUID.randomUUID(), roomId, organizerId,
                startUtc, endUtc, timezoneId, title);
        meetingRepo.save(meeting);

        saveAttendees(meeting.getId(), attendeeIds);
        conflictChecker.addBooking(roomId, startUtc, endUtc);

        return meeting;
    }

    // ========================================================================
    // BOOK RECURRING
    // ========================================================================

    public RecurringBookingResult bookRecurring(UUID roomId, UUID organizerId,
                                                 LocalDateTime localStart, LocalDateTime localEnd,
                                                 String timezoneId, String title,
                                                 RecurrenceRule rule, LocalDate horizonEnd) {
        return bookRecurring(roomId, organizerId, List.of(), localStart, localEnd, timezoneId, title, rule, horizonEnd);
    }

    /**
     * Book a recurring meeting series with optional attendees.
     *
     * Flow:
     * 1. Expand recurrence rule into concrete dates (pure date math)
     * 2. Convert each date to UTC using TimezoneResolver (DST-aware)
     * 3. Batch-check all occurrences against the room's conflict index
     * 4. If any conflict → return the conflict list, don't persist anything (all-or-nothing)
     * 5. If all clear → persist rule + meeting + attendees + register all in the index
     */
    public RecurringBookingResult bookRecurring(UUID roomId, UUID organizerId, List<UUID> attendeeIds,
                                                 LocalDateTime localStart, LocalDateTime localEnd,
                                                 String timezoneId, String title,
                                                 RecurrenceRule rule, LocalDate horizonEnd) {

        ZoneId zone = ZoneId.of(timezoneId);
        LocalDate dtstart = localStart.toLocalDate();
        Duration meetingDuration = Duration.between(localStart, localEnd);

        // 1. Expand — pure date math, no DB, no timezone awareness
        RecurrenceExpander expander = expanders.get(rule.getFreq());
        if (expander == null) {
            throw new IllegalArgumentException("Unsupported frequency: " + rule.getFreq());
        }
        List<LocalDate> occurrenceDates = expander.expand(rule, dtstart, horizonEnd);

        // 2. Convert each occurrence to UTC — DST-aware
        List<Interval> proposedIntervals = new ArrayList<>();
        for (LocalDate date : occurrenceDates) {
            LocalDateTime occurrenceLocal = LocalDateTime.of(date, localStart.toLocalTime());
            Instant occStart = timezoneResolver.toUtc(occurrenceLocal, zone);
            Instant occEnd = occStart.plus(meetingDuration);
            proposedIntervals.add(new Interval(occStart, occEnd));
        }

        // 3. Batch conflict check — one fetch, n in-memory checks
        List<Instant> conflicts = conflictChecker.findConflicts(roomId, proposedIntervals);
        if (!conflicts.isEmpty()) {
            // Convert conflict instants back to local dates for the response
            List<LocalDate> conflictDates = conflicts.stream()
                    .map(inst -> timezoneResolver.toLocal(inst, zone).toLocalDate())
                    .toList();
            return new RecurringBookingResult(false, null, conflictDates);
        }

        // 4. Persist — rule first (FK target), then meeting, then attendees
        ruleRepo.save(rule);

        Instant firstStart = proposedIntervals.get(0).start;
        Instant firstEnd = proposedIntervals.get(0).end;
        Meeting meeting = new Meeting(UUID.randomUUID(), roomId, organizerId,
                firstStart, firstEnd, timezoneId, title);
        meeting.setRecurrenceRuleId(rule.getId());
        meetingRepo.save(meeting);

        saveAttendees(meeting.getId(), attendeeIds);

        // 5. Register all occurrences in the in-memory conflict index
        for (Interval interval : proposedIntervals) {
            conflictChecker.addBooking(roomId, interval.start, interval.end);
        }

        return new RecurringBookingResult(true, meeting, List.of());
    }

    private void saveAttendees(UUID meetingId, List<UUID> attendeeIds) {
        if (attendeeIds != null && !attendeeIds.isEmpty()) {
            List<Attendee> attendees = attendeeIds.stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .map(attId -> new Attendee(UUID.randomUUID(), meetingId, attId))
                    .toList();
            attendeeRepo.saveAll(attendees);
        }
    }

    public List<Attendee> getAttendees(UUID meetingId) {
        return attendeeRepo.findByMeetingId(meetingId);
    }

    // ========================================================================
    // EDIT OCCURRENCE (THIS / THIS_AND_FUTURE / ALL)
    // ========================================================================

    /**
     * Edit a single occurrence, a range of future occurrences, or the entire series.
     *
     * THIS:             Insert a MeetingException (MODIFIED) for that one date.
     * THIS_AND_FUTURE:  Split the series — truncate old, create new from this date.
     * ALL:              Edit the master Meeting/Rule directly, re-validate all future.
     */
    public void editOccurrence(UUID meetingId, LocalDate occurrenceDate,
                               EditScope scope, LocalDateTime newLocalStart,
                               LocalDateTime newLocalEnd) {

        Meeting meeting = meetingRepo.findById(meetingId)
                .orElseThrow(() -> new IllegalArgumentException("Meeting not found: " + meetingId));

        switch (scope) {
            case THIS -> editThis(meeting, occurrenceDate, newLocalStart, newLocalEnd);
            case THIS_AND_FUTURE -> editThisAndFuture(meeting, occurrenceDate, newLocalStart, newLocalEnd);
            case ALL -> editAll(meeting, newLocalStart, newLocalEnd);
        }
    }

    /**
     * THIS scope: override a single occurrence without touching the series.
     * Insert a MODIFIED exception — the occurrence on that date gets new times,
     * all other occurrences are unaffected.
     */
    private void editThis(Meeting meeting, LocalDate occurrenceDate,
                          LocalDateTime newLocalStart, LocalDateTime newLocalEnd) {

        ZoneId zone = ZoneId.of(meeting.getTimezoneId());
        Instant newStartUtc = timezoneResolver.toUtc(newLocalStart, zone);
        Instant newEndUtc = timezoneResolver.toUtc(newLocalEnd, zone);

        // Conflict-check the new time for this single occurrence
        if (conflictChecker.hasConflict(meeting.getRoomId(), newStartUtc, newEndUtc)) {
            throw new BookingConflictException(
                    "New time conflicts with an existing booking on " + occurrenceDate);
        }

        // Remove the old occurrence from the conflict index
        removeOccurrenceFromIndex(meeting, occurrenceDate);

        // Create the exception record
        MeetingException exception = new MeetingException(
                UUID.randomUUID(), meeting.getId(), occurrenceDate, ExceptionType.MODIFIED);
        exception.setNewStartUtc(newStartUtc);
        exception.setNewEndUtc(newEndUtc);
        exceptionRepo.save(exception);

        // Add the new time to the conflict index
        conflictChecker.addBooking(meeting.getRoomId(), newStartUtc, newEndUtc);
    }

    /**
     * THIS_AND_FUTURE scope: split the series at occurrenceDate.
     *
     * 1. Truncate the old series: set its rule's UNTIL to occurrenceDate - 1
     * 2. Create a NEW meeting + rule starting at occurrenceDate with the new times
     * 3. Move any exceptions >= occurrenceDate from old series to new series
     * 4. Re-validate the new series against conflicts
     *
     * Invariant: after the split, oldSeries.until < newSeries.dtstart,
     * and no occurrence date exists in both expansions.
     */
    private void editThisAndFuture(Meeting meeting, LocalDate occurrenceDate,
                                   LocalDateTime newLocalStart, LocalDateTime newLocalEnd) {

        ZoneId zone = ZoneId.of(meeting.getTimezoneId());
        UUID roomId = meeting.getRoomId();

        // Remove all future occurrences (>= occurrenceDate) from the conflict index
        removeFutureOccurrencesFromIndex(meeting, occurrenceDate);

        // 1. Truncate old series
        if (meeting.getRecurrenceRuleId() != null) {
            ruleRepo.updateUntil(meeting.getRecurrenceRuleId(),
                    occurrenceDate.minusDays(1));
        }

        // 2. Create new series with modified times
        RecurrenceRule oldRule = ruleRepo.findById(meeting.getRecurrenceRuleId())
                .orElseThrow();

        // Copy the pattern, clear the until (the new series has its own horizon)
        RecurrenceRule newRule = new RecurrenceRule(UUID.randomUUID(), oldRule.getFreq());
        newRule.setIntervalN(oldRule.getIntervalN());
        if (oldRule.getByDay() != null) newRule.setByDay(oldRule.getByDay().clone());
        if (oldRule.getByMonthDay() != null) newRule.setByMonthDay(oldRule.getByMonthDay());
        if (oldRule.getBySetPos() != null) newRule.setBySetPos(oldRule.getBySetPos());
        if (oldRule.getCount() != null) newRule.setCount(oldRule.getCount());
        // Don't copy until — the truncated old rule already has it
        ruleRepo.save(newRule);

        Instant newStartUtc = timezoneResolver.toUtc(newLocalStart, zone);
        Instant newEndUtc = timezoneResolver.toUtc(newLocalEnd, zone);

        Meeting newMeeting = new Meeting(UUID.randomUUID(), roomId,
                meeting.getOrganizerId(), newStartUtc, newEndUtc,
                meeting.getTimezoneId(), meeting.getTitle());
        newMeeting.setRecurrenceRuleId(newRule.getId());
        newMeeting.setParentMeetingId(meeting.getId()); // audit trail
        meetingRepo.save(newMeeting);

        // 3. Expand the new series and conflict-check
        Duration meetingDuration = Duration.between(newLocalStart, newLocalEnd);
        RecurrenceExpander expander = expanders.get(newRule.getFreq());
        // Use a 12-month horizon from the split point
        LocalDate newHorizon = occurrenceDate.plusMonths(12);
        List<LocalDate> newDates = expander.expand(newRule, occurrenceDate, newHorizon);

        for (LocalDate date : newDates) {
            LocalDateTime occLocal = LocalDateTime.of(date, newLocalStart.toLocalTime());
            Instant occStart = timezoneResolver.toUtc(occLocal, zone);
            Instant occEnd = occStart.plus(meetingDuration);

            if (conflictChecker.hasConflict(roomId, occStart, occEnd)) {
                throw new BookingConflictException(
                        "New time conflicts with an existing booking on " + date);
            }
            conflictChecker.addBooking(roomId, occStart, occEnd);
        }

        // 4. Move exceptions >= occurrenceDate to the new series
        // (handled at DB level — not strictly needed for conflict index,
        //  but important for data integrity)
        List<MeetingException> movedExceptions =
                exceptionRepo.findByMasterIdFromDate(meeting.getId(), occurrenceDate);
        for (MeetingException ex : movedExceptions) {
            MeetingException newEx = new MeetingException(
                    UUID.randomUUID(), newMeeting.getId(),
                    ex.getOriginalOccurrenceDate(), ex.getType());
            if (ex.getNewStartUtc() != null) newEx.setNewStartUtc(ex.getNewStartUtc());
            if (ex.getNewEndUtc() != null) newEx.setNewEndUtc(ex.getNewEndUtc());
            exceptionRepo.save(newEx);
        }
    }

    /**
     * ALL scope: edit the master meeting directly, re-validate everything.
     *
     * Must re-check ALL future occurrences against conflicts because a time
     * change on the whole series can introduce new conflicts.
     * Don't skip conflict checking just because it's an edit, not a create.
     */
    private void editAll(Meeting meeting, LocalDateTime newLocalStart,
                         LocalDateTime newLocalEnd) {

        ZoneId zone = ZoneId.of(meeting.getTimezoneId());
        UUID roomId = meeting.getRoomId();

        // Remove all current occurrences from the conflict index
        removeAllOccurrencesFromIndex(meeting);

        // Update the master meeting's times
        Instant newStartUtc = timezoneResolver.toUtc(newLocalStart, zone);
        Instant newEndUtc = timezoneResolver.toUtc(newLocalEnd, zone);
        meeting.setStartUtc(newStartUtc);
        meeting.setEndUtc(newEndUtc);

        // Re-expand and re-validate
        if (meeting.getRecurrenceRuleId() != null) {
            RecurrenceRule rule = ruleRepo.findById(meeting.getRecurrenceRuleId())
                    .orElseThrow();
            RecurrenceExpander expander = expanders.get(rule.getFreq());
            Duration meetingDuration = Duration.between(newLocalStart, newLocalEnd);
            LocalDate dtstart = newLocalStart.toLocalDate();
            LocalDate horizon = dtstart.plusMonths(12);

            List<LocalDate> dates = expander.expand(rule, dtstart, horizon);
            for (LocalDate date : dates) {
                LocalDateTime occLocal = LocalDateTime.of(date, newLocalStart.toLocalTime());
                Instant occStart = timezoneResolver.toUtc(occLocal, zone);
                Instant occEnd = occStart.plus(meetingDuration);

                if (conflictChecker.hasConflict(roomId, occStart, occEnd)) {
                    throw new BookingConflictException(
                            "New time conflicts with an existing booking on " + date);
                }
                conflictChecker.addBooking(roomId, occStart, occEnd);
            }
        } else {
            // Single meeting — just re-check the one interval
            if (conflictChecker.hasConflict(roomId, newStartUtc, newEndUtc)) {
                throw new BookingConflictException(
                        "New time conflicts with an existing booking");
            }
            conflictChecker.addBooking(roomId, newStartUtc, newEndUtc);
        }

        // Persist with optimistic lock
        boolean updated = meetingRepo.updateWithVersion(meeting, meeting.getVersion());
        if (!updated) {
            throw new StaleVersionException(
                    "Meeting was modified by another request — reload and retry");
        }
    }

    // ========================================================================
    // CANCEL OCCURRENCE
    // ========================================================================

    public void cancelOccurrence(UUID meetingId, LocalDate occurrenceDate, EditScope scope) {
        Meeting meeting = meetingRepo.findById(meetingId)
                .orElseThrow(() -> new IllegalArgumentException("Meeting not found"));

        switch (scope) {
            case THIS -> {
                removeOccurrenceFromIndex(meeting, occurrenceDate);
                MeetingException exception = new MeetingException(
                        UUID.randomUUID(), meeting.getId(), occurrenceDate,
                        ExceptionType.CANCELLED);
                exceptionRepo.save(exception);
            }
            case THIS_AND_FUTURE -> {
                removeFutureOccurrencesFromIndex(meeting, occurrenceDate);
                if (meeting.getRecurrenceRuleId() != null) {
                    ruleRepo.updateUntil(meeting.getRecurrenceRuleId(),
                            occurrenceDate.minusDays(1));
                }
            }
            case ALL -> {
                removeAllOccurrencesFromIndex(meeting);
                meeting.setStatus(MeetingStatus.CANCELLED);
                meetingRepo.updateWithVersion(meeting, meeting.getVersion());
            }
        }
    }

    // ========================================================================
    // CONFLICT INDEX HELPERS
    // ========================================================================

    /**
     * Remove a single occurrence from the conflict index by recomputing its
     * UTC time from the occurrence date and the meeting's stored time/zone.
     */
    private void removeOccurrenceFromIndex(Meeting meeting, LocalDate date) {
        ZoneId zone = ZoneId.of(meeting.getTimezoneId());
        LocalDateTime localStart = timezoneResolver.toLocal(meeting.getStartUtc(), zone);
        LocalDateTime occLocal = LocalDateTime.of(date, localStart.toLocalTime());
        Instant occStart = timezoneResolver.toUtc(occLocal, zone);
        Duration duration = Duration.between(meeting.getStartUtc(), meeting.getEndUtc());
        Instant occEnd = occStart.plus(duration);
        conflictChecker.removeBooking(meeting.getRoomId(), occStart, occEnd);
    }

    /**
     * Remove all occurrences from occurrenceDate onward.
     */
    private void removeFutureOccurrencesFromIndex(Meeting meeting, LocalDate fromDate) {
        if (meeting.getRecurrenceRuleId() == null) return;

        RecurrenceRule rule = ruleRepo.findById(meeting.getRecurrenceRuleId()).orElseThrow();
        RecurrenceExpander expander = expanders.get(rule.getFreq());
        ZoneId zone = ZoneId.of(meeting.getTimezoneId());
        LocalDateTime localStart = timezoneResolver.toLocal(meeting.getStartUtc(), zone);
        Duration duration = Duration.between(meeting.getStartUtc(), meeting.getEndUtc());

        LocalDate horizon = fromDate.plusMonths(12);
        List<LocalDate> dates = expander.expand(rule, fromDate, horizon);

        for (LocalDate date : dates) {
            LocalDateTime occLocal = LocalDateTime.of(date, localStart.toLocalTime());
            Instant occStart = timezoneResolver.toUtc(occLocal, zone);
            Instant occEnd = occStart.plus(duration);
            conflictChecker.removeBooking(meeting.getRoomId(), occStart, occEnd);
        }
    }

    /**
     * Remove all occurrences of a meeting (single or entire series).
     */
    private void removeAllOccurrencesFromIndex(Meeting meeting) {
        ZoneId zone = ZoneId.of(meeting.getTimezoneId());
        LocalDateTime localStart = timezoneResolver.toLocal(meeting.getStartUtc(), zone);
        Duration duration = Duration.between(meeting.getStartUtc(), meeting.getEndUtc());

        if (meeting.getRecurrenceRuleId() == null) {
            // Single meeting — just remove it
            conflictChecker.removeBooking(meeting.getRoomId(),
                    meeting.getStartUtc(), meeting.getEndUtc());
            return;
        }

        RecurrenceRule rule = ruleRepo.findById(meeting.getRecurrenceRuleId()).orElseThrow();
        RecurrenceExpander expander = expanders.get(rule.getFreq());
        LocalDate dtstart = localStart.toLocalDate();
        LocalDate horizon = dtstart.plusMonths(12);

        List<LocalDate> dates = expander.expand(rule, dtstart, horizon);
        for (LocalDate date : dates) {
            LocalDateTime occLocal = LocalDateTime.of(date, localStart.toLocalTime());
            Instant occStart = timezoneResolver.toUtc(occLocal, zone);
            Instant occEnd = occStart.plus(duration);
            conflictChecker.removeBooking(meeting.getRoomId(), occStart, occEnd);
        }
    }

    // ========================================================================
    // TYPES
    // ========================================================================

    public enum EditScope { THIS, THIS_AND_FUTURE, ALL }

    public record RecurringBookingResult(boolean success, Meeting meeting,
                                         List<LocalDate> conflictDates) {}

    public static class BookingConflictException extends RuntimeException {
        public BookingConflictException(String message) { super(message); }
    }

    public static class StaleVersionException extends RuntimeException {
        public StaleVersionException(String message) { super(message); }
    }
}
