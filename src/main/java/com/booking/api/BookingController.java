package com.booking.api;

import com.booking.domain.Meeting;
import com.booking.domain.RecurrenceRule;
import com.booking.service.BookingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/rooms")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping("/{roomId}/bookings")
    public ResponseEntity<?> bookSingle(@PathVariable UUID roomId, @RequestBody BookingRequest request) {
        try {
            Meeting meeting = bookingService.bookSingle(roomId, request.organizerId(),
                    request.start(), request.end(), request.timezoneId(), request.title());
            return ResponseEntity.status(201).body(meeting);
        } catch (BookingService.BookingConflictException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{roomId}/bookings/recurring")
    public ResponseEntity<?> bookRecurring(@PathVariable UUID roomId, @RequestBody RecurringBookingRequest request) {
        var result = bookingService.bookRecurring(roomId, request.organizerId(),
                request.start(), request.end(), request.timezoneId(), request.title(),
                request.toRecurrenceRule(), request.horizonEnd());
        return result.success()
                ? ResponseEntity.status(201).body(result)
                : ResponseEntity.status(409).body(Map.of("conflictDates", result.conflictDates()));
    }

    @PatchMapping("/bookings/{bookingId}")
    public ResponseEntity<?> editOccurrence(@PathVariable UUID bookingId,
            @RequestParam BookingService.EditScope scope, @RequestBody EditRequest request) {
        return ResponseEntity.ok().build(); // wire to BookingService edit methods once persistence is ready
    }

    // Request DTOs — kept in this file for now since they're small; split
    // into their own files if this grows past ~4 of them.
    public record BookingRequest(UUID organizerId, LocalDateTime start, LocalDateTime end,
                                   String timezoneId, String title) {}

    public record RecurringBookingRequest(UUID organizerId, LocalDateTime start, LocalDateTime end,
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