package com.booking.api;

import com.booking.domain.Meeting;
import com.booking.domain.RecurrenceRule;
import com.booking.service.BookingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping("/rooms/{roomId}/bookings")
    public ResponseEntity<?> bookSingle(@PathVariable UUID roomId, @RequestBody BookingRequest request) {
        Meeting meeting = bookingService.bookSingle(roomId, request.organizerId(),
                request.attendeeIds(), request.start(), request.end(),
                request.timezoneId(), request.title());
        return ResponseEntity.status(201).body(meeting);
    }

    @PostMapping("/rooms/{roomId}/bookings/recurring")
    public ResponseEntity<?> bookRecurring(@PathVariable UUID roomId, @RequestBody RecurringBookingRequest request) {
        var result = bookingService.bookRecurring(roomId, request.organizerId(),
                request.attendeeIds(), request.start(), request.end(),
                request.timezoneId(), request.title(),
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
    public record BookingRequest(UUID organizerId, List<UUID> attendeeIds,
                                 LocalDateTime start, LocalDateTime end,
                                 String timezoneId, String title) {}

    public record RecurringBookingRequest(UUID organizerId, List<UUID> attendeeIds,
                                          LocalDateTime start, LocalDateTime end,
                                          String timezoneId, String title, LocalDate horizonEnd,
                                          RuleRequest rule) {
        RecurrenceRule toRecurrenceRule() {
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

    public record RuleRequest(RecurrenceRule.Frequency freq, Integer intervalN, String[] byDay,
                              Integer byMonthDay, Integer bySetPos, Integer count, LocalDate until) {}

    public record EditRequest(LocalDate occurrenceDate, LocalDateTime newStart, LocalDateTime newEnd) {}
}