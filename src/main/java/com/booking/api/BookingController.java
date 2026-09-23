package com.booking.api;

import com.booking.domain.Meeting;
import com.booking.domain.RecurrenceRule;
import com.booking.domain.User;
import com.booking.repository.UserRepository;
import com.booking.service.BookingService;
import com.fasterxml.jackson.annotation.JsonAlias;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class BookingController {

    private final BookingService bookingService;
    private final UserRepository userRepo;

    public BookingController(BookingService bookingService, UserRepository userRepo) {
        this.bookingService = bookingService;
        this.userRepo = userRepo;
    }

    @PostMapping("/rooms/{roomId}/bookings")
    public ResponseEntity<?> bookSingle(@PathVariable UUID roomId,
                                        @RequestBody BookingRequest request,
                                        Principal principal) {
        UUID organizerId = request.organizerId();
        if (organizerId == null && principal != null) {
            organizerId = userRepo.findByEmail(principal.getName()).map(User::getId).orElse(null);
        }
        if (organizerId == null) {
            throw new IllegalArgumentException("Organizer ID could not be determined. Please authenticate or provide organizerId.");
        }
        if (request.start() == null || request.end() == null) {
            throw new IllegalArgumentException("Both start time and end time are required.");
        }
        String timezone = (request.timezoneId() != null && !request.timezoneId().isBlank())
                ? request.timezoneId()
                : "Asia/Kolkata";

        Meeting meeting = bookingService.bookSingle(roomId, organizerId,
                request.attendeeIds() != null ? request.attendeeIds() : List.of(),
                request.start(), request.end(),
                timezone, request.title());
        return ResponseEntity.status(201).body(meeting);
    }

    @PostMapping("/rooms/{roomId}/bookings/recurring")
    public ResponseEntity<?> bookRecurring(@PathVariable UUID roomId,
                                           @RequestBody RecurringBookingRequest request,
                                           Principal principal) {
        UUID organizerId = request.organizerId();
        if (organizerId == null && principal != null) {
            organizerId = userRepo.findByEmail(principal.getName()).map(User::getId).orElse(null);
        }
        if (organizerId == null) {
            throw new IllegalArgumentException("Organizer ID could not be determined. Please authenticate or provide organizerId.");
        }
        if (request.start() == null || request.end() == null) {
            throw new IllegalArgumentException("Both start time and end time are required.");
        }
        String timezone = (request.timezoneId() != null && !request.timezoneId().isBlank())
                ? request.timezoneId()
                : "Asia/Kolkata";

        var result = bookingService.bookRecurring(roomId, organizerId,
                request.attendeeIds() != null ? request.attendeeIds() : List.of(),
                request.start(), request.end(),
                timezone, request.title(),
                request.toRecurrenceRule(), request.horizonEnd());
        return result.success()
                ? ResponseEntity.status(201).body(result)
                : ResponseEntity.status(409).body(Map.of("conflictDates", result.conflictDates()));
    }

    @PatchMapping("/bookings/{bookingId}")
    public ResponseEntity<?> editOccurrence(@PathVariable UUID bookingId,
                                            @RequestParam BookingService.EditScope scope,
                                            @RequestBody EditRequest request) {
        bookingService.editOccurrence(bookingId, request.occurrenceDate(), scope,
                request.newStart(), request.newEnd());
        return ResponseEntity.ok(Map.of(
                "message", "Occurrence edited successfully",
                "scope", scope.name()
        ));
    }

    @DeleteMapping("/bookings/{bookingId}")
    public ResponseEntity<?> cancelOccurrence(@PathVariable UUID bookingId,
                                              @RequestParam BookingService.EditScope scope,
                                              @RequestParam(required = false) LocalDate occurrenceDate) {
        bookingService.cancelOccurrence(bookingId, occurrenceDate, scope);
        return ResponseEntity.ok(Map.of(
                "message", "Occurrence cancelled successfully",
                "scope", scope.name()
        ));
    }

    @GetMapping("/bookings/{bookingId}/attendees")
    public ResponseEntity<?> getAttendees(@PathVariable UUID bookingId) {
        return ResponseEntity.ok(bookingService.getAttendees(bookingId));
    }

    // Request DTOs
    public record BookingRequest(
            @JsonAlias({"userId", "organizer_id"}) UUID organizerId,
            @JsonAlias({"attendees", "attendee_ids"}) List<UUID> attendeeIds,
            @JsonAlias({"startTime", "start_time"}) LocalDateTime start,
            @JsonAlias({"endTime", "end_time"}) LocalDateTime end,
            @JsonAlias({"timezone", "timeZone", "time_zone", "tz"}) String timezoneId,
            String title) {}

    public record RecurringBookingRequest(
            @JsonAlias({"userId", "organizer_id"}) UUID organizerId,
            @JsonAlias({"attendees", "attendee_ids"}) List<UUID> attendeeIds,
            @JsonAlias({"startTime", "start_time"}) LocalDateTime start,
            @JsonAlias({"endTime", "end_time"}) LocalDateTime end,
            @JsonAlias({"timezone", "timeZone", "time_zone", "tz"}) String timezoneId,
            String title,
            @JsonAlias({"horizon", "horizon_end"}) LocalDate horizonEnd,
            RuleRequest rule) {
        RecurrenceRule toRecurrenceRule() {
            if (rule == null || rule.freq() == null) {
                throw new IllegalArgumentException("Recurrence rule with frequency (DAILY, WEEKLY, MONTHLY) is required");
            }
            RecurrenceRule r = new RecurrenceRule(UUID.randomUUID(), rule.freq());
            if (rule.intervalN() != null) r.setIntervalN(rule.intervalN());
            if (rule.byDay() != null) r.setByDay(rule.byDay());
            if (rule.byMonthDay() != null) r.setByMonthDay(rule.byMonthDay());
            if (rule.bySetPos() != null) r.setBySetPos(rule.bySetPos());
            if (rule.count() != null) r.setCount(rule.count());
            if (rule.until() != null) r.setUntil(rule.until());
            return r;
        }
    }

    public record RuleRequest(
            @JsonAlias({"frequency"}) RecurrenceRule.Frequency freq,
            @JsonAlias({"interval", "interval_n"}) Integer intervalN,
            @JsonAlias({"by_day", "byDays"}) String[] byDay,
            @JsonAlias({"by_month_day", "dayOfMonth"}) Integer byMonthDay,
            @JsonAlias({"by_set_pos", "setPos"}) Integer bySetPos,
            Integer count,
            LocalDate until) {}

    public record EditRequest(
            @JsonAlias({"date", "occurrence_date"}) LocalDate occurrenceDate,
            @JsonAlias({"startTime", "start_time", "start", "new_start"}) LocalDateTime newStart,
            @JsonAlias({"endTime", "end_time", "end", "new_end"}) LocalDateTime newEnd) {}
}