# retrouver

Meeting room booking system with recurring meeting support (RRULE-based expansion), conflict detection, timezone/DST handling, and series editing (this/future/all) — Java 21 + Spring Boot + SQLite.

## Quick Start

```bash
# Prerequisites: Java 17+, Maven 3.9+
mvn spring-boot:run
# Server starts on http://localhost:8080
```

## API Reference

### Auth

```
POST /auth/register
Body: { "name": "Raj", "email": "raj@lpu.in", "timezone": "Asia/Kolkata", "password": "secret" }
→ 201 { "token": "eyJ...", "userId": "..." }

POST /auth/login
Body: { "email": "raj@lpu.in", "password": "secret" }
→ 200 { "token": "eyJ...", "userId": "..." }
```

All endpoints below require `Authorization: Bearer <token>`.

### Bookings

```
POST   /rooms/{roomId}/bookings               → book a single meeting
POST   /rooms/{roomId}/bookings/recurring      → book a recurring series
PATCH  /bookings/{bookingId}?scope=THIS|THIS_AND_FUTURE|ALL → edit occurrence(s)
DELETE /bookings/{bookingId}?scope=THIS|THIS_AND_FUTURE|ALL → cancel occurrence(s)
```

**Single booking request:**
```json
{
  "organizerId": "uuid",
  "start": "2026-01-15T10:00:00",
  "end": "2026-01-15T11:00:00",
  "timezoneId": "Asia/Kolkata",
  "title": "Sprint Planning"
}
```

**Recurring booking request:**
```json
{
  "organizerId": "uuid",
  "start": "2026-01-15T10:00:00",
  "end": "2026-01-15T11:00:00",
  "timezoneId": "America/New_York",
  "title": "Weekly Standup",
  "horizonEnd": "2027-01-15",
  "rule": {
    "freq": "WEEKLY",
    "intervalN": 1,
    "byDay": ["MO", "WE", "FR"],
    "count": 52
  }
}
```

**Conflict response (409):**
```json
{
  "conflictDates": ["2026-02-16", "2026-03-09"]
}
```

## Architecture

```
Client request
  → BookingController (API layer — REST endpoints, request DTOs)
    → BookingService (orchestrator — the ONLY place all concerns meet)
      → RecurrenceExpander (strategy pattern: Daily/Weekly/Monthly)
      → ConflictChecker (sorted list + binary search per room)
      → TimezoneResolver (DST gap/fold handling via java.time ZoneRules)
      → Repositories (JdbcTemplate → SQLite)
    → Domain entities (plain POJOs — Meeting, RecurrenceRule, Room, User, etc.)
  → SQLite database (retrouver.db)
```

**Key separation of concerns:** RecurrenceExpander never knows about conflicts. ConflictChecker never knows about recurrence. TimezoneResolver never knows about the domain. They only meet inside BookingService.

## Data Model

6 entities: User, Room, Meeting, RecurrenceRule, MeetingException, Attendee.

- **Meeting** stores `start_utc`/`end_utc` (UTC) + `timezone_id` (IANA zone) — UTC for indexing/comparison, zone ID for DST-correct local time derivation.
- **RecurrenceRule** stores only the pattern (FREQ/INTERVAL/BYDAY/BYMONTHDAY/BYSETPOS/UNTIL/COUNT) — no dtstart, which lives on Meeting per RFC 5545.
- **MeetingException** handles per-occurrence overrides (MODIFIED or CANCELLED).
- **Meeting.version** enables optimistic locking for concurrent booking safety.

## Core Algorithms

### Recurrence Expansion
- **Strategy pattern**: `RecurrenceExpander` interface with `DailyStrategy`, `WeeklyStrategy`, `MonthlyStrategy`. Adding YEARLY = one new class, zero changes elsewhere.
- **Monthly BYMONTHDAY=31**: skips months with fewer days (RFC 5545 behavior) — does NOT clamp to the last day.
- **Monthly BYDAY+BYSETPOS**: computes "2nd Wednesday" / "last Friday" directly using negative indexing.
- **Weekly N-week interval**: anchored to dtstart's week to prevent drift.

### Conflict Checking — O(log n)
- **Sorted list of intervals per room**, binary search for insertion point, check only two neighbors.
- **Half-open intervals**: `[s1,e1)` and `[s2,e2)` overlap iff `s1 < e2 AND s2 < e1`. End time is exclusive — 11:00-end + 11:00-start = no conflict.
- **Batch mode**: for recurring series, fetch the room's index once, check all ~26 occurrences in-memory.

### Timezone / DST Handling
- **Spring-forward gap** (e.g. 02:30 doesn't exist): shift forward by gap duration → 03:30.
- **Fall-back overlap** (e.g. 01:30 happens twice): pick the first occurrence (earlier UTC instant).
- Uses `java.time.ZoneRules.getTransition()` — no manual offset arithmetic.

### Series Editing (THIS / THIS_AND_FUTURE / ALL)
- **THIS**: insert a `MeetingException` (MODIFIED or CANCELLED). Master rule untouched.
- **THIS_AND_FUTURE**: split the series — truncate old rule's UNTIL, create new Meeting + Rule starting at the edit date, move exceptions ≥ that date to the new series.
- **ALL**: edit master Meeting/Rule directly, re-validate ALL future occurrences against conflicts.

## Time/Space Complexity

| Operation | Time | Space |
|-----------|------|-------|
| Single booking conflict check | O(log n) | O(1) |
| Recurring series expansion (k occurrences) | O(k) for Daily, O(days) for Weekly | O(k) |
| Batch conflict check (k occurrences) | O(k log n) | O(k) |
| Series split (THIS_AND_FUTURE) | O(k log n) for re-validation | O(k) |
| Binary search insertion | O(log n) search + O(n) shift | O(1) |

Where n = number of existing bookings in a room, k = number of occurrences in a series.

## Concurrency / Fault Tolerance

- **Optimistic locking**: `UPDATE ... WHERE version = ?` — prevents the race where two requests both pass conflict-checking and both write. Zero-row update = stale → reject and retry.
- **WAL mode**: `PRAGMA journal_mode=WAL` — lets readers proceed concurrently with a writer (SQLite default blocks readers during writes).
- **FK enforcement**: `PRAGMA foreign_keys=ON` — SQLite defaults this to OFF. Without it, RESTRICT/CASCADE constraints are silently decorative.
- **All-or-nothing recurring booking**: if any occurrence in a series conflicts, the entire series is rejected — no partial bookings.

## Auth

- **JWT (HS256)**: stateless — the token IS the session. No server-side session store.
- **BCrypt**: deliberately slow password hashing. Prevents brute-force on leaked hashes.
- **Same login error**: "Invalid email or password" for both unknown-email and wrong-password — prevents email enumeration.
- **Endpoint protection**: `/auth/**` is public; everything else requires `Authorization: Bearer <token>`.

## Design Decisions

See [DECISIONS.md](DECISIONS.md) for the full reasoning behind each choice:

1. dtstart on Meeting, not RecurrenceRule (RFC 5545)
2. java.time over ical4j (hand-written expansion is the assignment's point)
3. Sorted list over interval tree (point-insertion checks only)
4. UTC + IANA zone ID (fixed offset breaks at DST transitions)
5. Half-open `[s,e)` overlap rule (correct boundary behavior)
6. Optimistic locking via version column (prevents concurrent booking race)
7. RESTRICT on Room FK, CASCADE on MeetingException FK
8. Spring Boot (DI + ecosystem)
9. JdbcTemplate over JPA (SQLite dialect issues, interview explainability)
10. Stateless JWT over sessions (no server-side state)

## Tech Stack

| Component | Choice | Why |
|-----------|--------|-----|
| Language | Java 17 | Switch expressions, records, text blocks |
| Framework | Spring Boot 3.3 | DI, embedded server, security, JDBC |
| Database | SQLite | Zero-config, file-based, sufficient for case study |
| Persistence | JdbcTemplate | Hand-written SQL, no ORM overhead |
| Auth | Spring Security + JJWT | Stateless JWT, BCrypt |
| Testing | JUnit 5 | Via spring-boot-starter-test |
| Build | Maven | Standard, no Gradle complexity |

## Project Structure

```
src/main/java/com/booking/
├── BookingApplication.java          # Spring Boot entry point
├── api/
│   └── BookingController.java       # REST endpoints + request DTOs
├── auth/
│   ├── AuthController.java          # Register + login
│   ├── JwtService.java              # Token generation + validation
│   ├── JwtAuthFilter.java           # Bearer token extraction filter
│   └── SecurityConfig.java          # Spring Security configuration
├── conflict/
│   └── ConflictChecker.java         # Sorted interval list + binary search
├── domain/
│   ├── Meeting.java                 # Core entity (UTC times, version lock)
│   ├── MeetingException.java        # Per-occurrence override (MODIFIED/CANCELLED)
│   ├── RecurrenceRule.java          # Pattern-only value object (no dtstart)
│   ├── Room.java                    # Soft-delete via active flag
│   └── User.java                    # IANA timezone, password hash
├── recurrence/
│   ├── RecurrenceExpander.java      # Strategy interface
│   ├── DailyStrategy.java           # Every N days
│   ├── WeeklyStrategy.java          # Every N weeks on chosen weekdays
│   └── MonthlyStrategy.java         # By month day OR by day+setpos
├── repository/
│   ├── DatabaseInitializer.java     # SQLite DDL (CREATE TABLE IF NOT EXISTS)
│   ├── MeetingRepository.java       # CRUD + optimistic lock update
│   ├── MeetingExceptionRepository.java
│   ├── RecurrenceRuleRepository.java
│   ├── RoomRepository.java
│   └── UserRepository.java
├── service/
│   └── BookingService.java          # Orchestrator (book/edit/cancel)
└── timezone/
    └── TimezoneResolver.java        # DST gap/fold handling

src/test/java/com/booking/
├── conflict/
│   └── ConflictCheckerTest.java     # 16 tests (overlap, half-open, multi-room, batch)
├── recurrence/
│   └── RecurrenceExpanderTest.java  # 18 tests (all 3 strategies + edge cases)
└── timezone/
    └── TimezoneResolverTest.java    # 14 tests (gap, overlap, cross-DST recurring)
```
