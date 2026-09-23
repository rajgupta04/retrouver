package com.booking.service;

import com.booking.conflict.ConflictChecker;
import com.booking.domain.*;
import com.booking.domain.Meeting.MeetingStatus;
import com.booking.domain.RecurrenceRule.Frequency;
import com.booking.repository.*;
import com.booking.timezone.TimezoneResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock private MeetingRepository meetingRepo;
    @Mock private RecurrenceRuleRepository ruleRepo;
    @Mock private MeetingExceptionRepository exceptionRepo;
    @Mock private RoomRepository roomRepo;
    @Mock private AttendeeRepository attendeeRepo;

    private ConflictChecker conflictChecker;
    private TimezoneResolver timezoneResolver;
    private BookingService bookingService;

    private final UUID roomId = UUID.randomUUID();
    private final UUID organizerId = UUID.randomUUID();
    private final UUID attendee1 = UUID.randomUUID();
    private final UUID attendee2 = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        conflictChecker = new ConflictChecker();
        timezoneResolver = new TimezoneResolver();
        bookingService = new BookingService(
                meetingRepo, ruleRepo, exceptionRepo, roomRepo,
                attendeeRepo, conflictChecker, timezoneResolver
        );
    }

    @Test
    void bookSingle_success_persistsMeetingAndAttendees() {
        LocalDateTime start = LocalDateTime.of(2026, 5, 10, 10, 0);
        LocalDateTime end = LocalDateTime.of(2026, 5, 10, 11, 0);

        Meeting meeting = bookingService.bookSingle(
                roomId, organizerId, List.of(attendee1, attendee2),
                start, end, "Asia/Kolkata", "Quarterly Review"
        );

        assertThat(meeting).isNotNull();
        assertThat(meeting.getTitle()).isEqualTo("Quarterly Review");
        verify(meetingRepo).save(any(Meeting.class));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Attendee>> captor = ArgumentCaptor.forClass(List.class);
        verify(attendeeRepo).saveAll(captor.capture());
        List<Attendee> savedAttendees = captor.getValue();
        assertThat(savedAttendees).hasSize(2);
        assertThat(savedAttendees).extracting(Attendee::getUserId)
                .containsExactlyInAnyOrder(attendee1, attendee2);
    }

    @Test
    void bookSingle_conflict_throwsException() {
        LocalDateTime start = LocalDateTime.of(2026, 5, 10, 10, 0);
        LocalDateTime end = LocalDateTime.of(2026, 5, 10, 11, 0);

        bookingService.bookSingle(roomId, organizerId, List.of(), start, end, "Asia/Kolkata", "First");

        assertThatThrownBy(() -> bookingService.bookSingle(
                roomId, organizerId, List.of(), start, end, "Asia/Kolkata", "Second"
        )).isInstanceOf(BookingService.BookingConflictException.class);
    }

    @Test
    void bookRecurring_allOrNothing_failsEntireSeriesIfAnyOccurrenceConflicts() {
        // Pre-book May 12 10:00-11:00
        bookingService.bookSingle(
                roomId, organizerId, List.of(),
                LocalDateTime.of(2026, 5, 12, 10, 0),
                LocalDateTime.of(2026, 5, 12, 11, 0),
                "Asia/Kolkata", "Existing clash"
        );

        // Try booking daily series from May 10 to May 14 at 10:00-11:00
        RecurrenceRule dailyRule = new RecurrenceRule(UUID.randomUUID(), Frequency.DAILY);
        dailyRule.setIntervalN(1);
        dailyRule.setCount(5);

        BookingService.RecurringBookingResult result = bookingService.bookRecurring(
                roomId, organizerId, List.of(attendee1),
                LocalDateTime.of(2026, 5, 10, 10, 0),
                LocalDateTime.of(2026, 5, 10, 11, 0),
                "Asia/Kolkata", "Daily Standup",
                dailyRule, LocalDate.of(2026, 5, 15)
        );

        assertThat(result.success()).isFalse();
        assertThat(result.meeting()).isNull();
        assertThat(result.conflictDates()).containsExactly(LocalDate.of(2026, 5, 12));

        // Ensure nothing from the series was saved
        verify(ruleRepo, never()).save(any());
        verify(attendeeRepo, never()).saveAll(any());
    }

    @Test
    void bookRecurring_success_persistsRuleMeetingAndAttendees() {
        RecurrenceRule weeklyRule = new RecurrenceRule(UUID.randomUUID(), Frequency.WEEKLY);
        weeklyRule.setIntervalN(1);
        weeklyRule.setByDay(new String[]{"MO"});
        weeklyRule.setCount(3);

        BookingService.RecurringBookingResult result = bookingService.bookRecurring(
                roomId, organizerId, List.of(attendee1),
                LocalDateTime.of(2026, 5, 4, 9, 0),
                LocalDateTime.of(2026, 5, 4, 10, 0),
                "Asia/Kolkata", "Weekly Monday Sync",
                weeklyRule, LocalDate.of(2026, 5, 25)
        );

        assertThat(result.success()).isTrue();
        assertThat(result.meeting()).isNotNull();
        assertThat(result.conflictDates()).isEmpty();

        verify(ruleRepo).save(weeklyRule);
        verify(meetingRepo).save(any(Meeting.class));
        verify(attendeeRepo).saveAll(any());
    }

    @Test
    void editOccurrence_thisScope_createsException() {
        UUID meetingId = UUID.randomUUID();
        Meeting meeting = new Meeting(meetingId, roomId, organizerId,
                timezoneResolver.toUtc(LocalDateTime.of(2026, 5, 1, 10, 0), java.time.ZoneId.of("Asia/Kolkata")),
                timezoneResolver.toUtc(LocalDateTime.of(2026, 5, 1, 11, 0), java.time.ZoneId.of("Asia/Kolkata")),
                "Asia/Kolkata", "Sync");
        when(meetingRepo.findById(meetingId)).thenReturn(Optional.of(meeting));

        LocalDate occurrenceDate = LocalDate.of(2026, 5, 8);
        LocalDateTime newStart = LocalDateTime.of(2026, 5, 8, 14, 0);
        LocalDateTime newEnd = LocalDateTime.of(2026, 5, 8, 15, 0);

        bookingService.editOccurrence(
                meetingId, occurrenceDate, BookingService.EditScope.THIS,
                newStart, newEnd
        );

        verify(exceptionRepo).save(argThat(ex ->
                ex.getMasterMeetingId().equals(meetingId) &&
                ex.getOriginalOccurrenceDate().equals(occurrenceDate) &&
                ex.getType() == MeetingException.ExceptionType.MODIFIED
        ));
    }

    @Test
    void cancelOccurrence_thisScope_createsCancellationException() {
        UUID meetingId = UUID.randomUUID();
        Meeting meeting = new Meeting(meetingId, roomId, organizerId,
                timezoneResolver.toUtc(LocalDateTime.of(2026, 5, 1, 10, 0), java.time.ZoneId.of("Asia/Kolkata")),
                timezoneResolver.toUtc(LocalDateTime.of(2026, 5, 1, 11, 0), java.time.ZoneId.of("Asia/Kolkata")),
                "Asia/Kolkata", "Sync");
        when(meetingRepo.findById(meetingId)).thenReturn(Optional.of(meeting));

        LocalDate occurrenceDate = LocalDate.of(2026, 5, 8);
        bookingService.cancelOccurrence(meetingId, occurrenceDate, BookingService.EditScope.THIS);

        verify(exceptionRepo).save(argThat(ex ->
                ex.getMasterMeetingId().equals(meetingId) &&
                ex.getOriginalOccurrenceDate().equals(occurrenceDate) &&
                ex.getType() == MeetingException.ExceptionType.CANCELLED
        ));
    }

    @Test
    void cancelOccurrence_allScope_updatesStatusToCancelled() {
        UUID meetingId = UUID.randomUUID();
        Meeting meeting = new Meeting(meetingId, roomId, organizerId,
                timezoneResolver.toUtc(LocalDateTime.of(2026, 5, 1, 10, 0), java.time.ZoneId.of("Asia/Kolkata")),
                timezoneResolver.toUtc(LocalDateTime.of(2026, 5, 1, 11, 0), java.time.ZoneId.of("Asia/Kolkata")),
                "Asia/Kolkata", "Sync");
        meeting.setVersion(0);
        when(meetingRepo.findById(meetingId)).thenReturn(Optional.of(meeting));

        bookingService.cancelOccurrence(meetingId, null, BookingService.EditScope.ALL);

        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.CANCELLED);
        verify(meetingRepo).updateWithVersion(meeting, 0);
    }
}
