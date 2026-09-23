# Design Decisions

## dtstart placement
Meeting owns `dtstart`; RecurrenceRule only stores the repeat pattern
(FREQ, INTERVAL, BYDAY, BYMONTHDAY, BYSETPOS, UNTIL, COUNT) with no
date of its own. This mirrors RFC 5545 (iCalendar), where DTSTART and
RRULE are separate properties on an event, not nested inside each
other. It keeps RecurrenceRule reusable and makes series-splitting
(THIS_AND_FUTURE edits) clean — the old series keeps its dtstart, the
new series gets a fresh dtstart, and only the pattern fields need to
be copied across.

## java.time over a recurrence library (ical4j)
Used Java's built-in java.time package (LocalDate, ZonedDateTime,
ZoneId) instead of a third-party recurrence/iCalendar library like
ical4j. Writing the expansion algorithm and timezone/DST handling
myself is the actual point of this assignment — pulling in a library
that does RRULE expansion internally would hide the exact logic being
evaluated. java.time still gives correct, IANA-timezone-database-backed
DST handling, so there's no correctness trade-off, only a
"who writes the logic" trade-off, and that's deliberate here.

## Sorted list + binary search over an interval tree for conflict checking
Chose a sorted list of (start, end) intervals per room, with binary
search to find the insertion point, over a full interval tree.
An interval tree is the better structure for arbitrary overlapping
range queries at scale, but this system only needs point-insertion
conflict checks (does a new [start,end) overlap anything already
booked in this room), which a sorted list handles in O(log n) for the
search plus a constant check against the two neighboring intervals.
The interval tree's extra complexity (balancing, more code to get
right and explain) isn't justified by what this system actually needs
to do. If the system later needed arbitrary range overlap queries
(e.g. "list all conflicts in this month across all recurrence
expansions at once"), an interval tree would be the right upgrade.

## UTC storage + separate timezone_id
Meeting stores start_utc/end_utc for comparison and indexing, plus a
separate timezone_id (IANA zone, e.g. "Asia/Kolkata") for re-deriving
local wall-clock time. Storing a fixed UTC offset instead of a zone id
would break recurring meetings across DST transitions — the whole
point of storing the zone id is that "10am every Monday" should stay
10am local time even as the UTC offset underneath it changes twice a
year.

## Half-open interval overlap rule
Two intervals [s1, e1) and [s2, e2) are considered overlapping iff
s1 < e2 AND s2 < e1. Using half-open intervals (end time exclusive)
avoids a false-positive conflict when one meeting ends exactly when
another starts (e.g. 10:00-11:00 and 11:00-12:00 in the same room
should NOT be flagged as a clash).

## Optimistic locking via a version column
Meeting has a version integer column, incremented on every update.
Updates are done as UPDATE ... WHERE id = ? AND version = ?, and a
zero-row update means someone else modified the row first, so the
request is rejected and the client must retry. This is what actually
prevents two concurrent booking requests from both passing a conflict
check and then both writing — the transaction alone doesn't stop that
race without an explicit version check tied to the write.

## FK delete behavior: RESTRICT on Room, CASCADE on MeetingException
Deleting a Room is RESTRICT (blocked) if it has existing meetings —
deactivating a room (active=false) is the correct operation, not
deleting historical booking data. Deleting a Meeting, however,
CASCADEs to its MeetingException rows, because an exception to a
series is meaningless once the series itself is gone. Same "delete"
action, different cascade behavior, chosen per-relationship based on
whether the child data is still meaningful without its parent.

## Spring Boot as the framework
Chose Spring Boot over plain Java (raw HTTP server / Javalin / Spark) for
three reasons: (1) embedded Tomcat means zero deployment config — `mvn
spring-boot:run` and it's up; (2) dependency injection via constructor
injection keeps the layered architecture clean (Controller → Service →
Repository, each wired automatically); (3) Spring's ecosystem (Security,
JDBC, Test) covers auth, persistence, and testing without pulling in
unrelated third-party libraries. The trade-off is a heavier startup time
and more framework magic to explain, but the structure it imposes
(stereotype annotations, auto-configuration) is exactly what an interview
grader expects to see in a "production-style" backend.

## Spring JDBC (JdbcTemplate) over JPA/Hibernate
Used Spring's JdbcTemplate with hand-written SQL instead of JPA/Hibernate.
Three reasons: (1) SQLite has limited and unofficial JPA dialect support —
fighting Hibernate to generate correct SQLite DDL is wasted effort;
(2) the queries in this system are straightforward CRUD + a few indexed
lookups, so an ORM adds abstraction overhead without simplifying anything;
(3) hand-written SQL is easier to explain line-by-line in a live interview,
which is the whole evaluation context for this project.