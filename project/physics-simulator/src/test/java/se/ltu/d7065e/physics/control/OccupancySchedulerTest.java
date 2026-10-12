package se.ltu.d7065e.physics.control;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/** T-07: one weekday-aware schedule per room type, drawn from a seed so a run replays (R-3). */
class OccupancySchedulerTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    private static final LocalDate SATURDAY = LocalDate.of(2026, 10, 10);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 11);

    @Test
    @DisplayName("T-07: weekends are empty in every room")
    void shouldLeaveEveryRoomEmptyAtTheWeekend() {
        OccupancyScheduler scheduler = new OccupancyScheduler(42);

        assertAll(List.of(SATURDAY, SUNDAY).stream().flatMap(day -> Topology.ZONE_IDS.stream()
                .map(zone -> () -> assertEquals(0, occupantMinutes(scheduler, zone, day),
                        zone + " must be empty on " + day.getDayOfWeek()))));
    }

    @Test
    @DisplayName("T-07, T-15: every room is empty through the night, so CO2 decays to outdoor")
    void shouldLeaveEveryRoomEmptyThroughTheNight() {
        OccupancyScheduler scheduler = new OccupancyScheduler(42);

        assertAll(weekdays().stream().flatMap(day -> Topology.ZONE_IDS.stream()
                .map(zone -> () -> assertEquals(0,
                        occupantMinutesBetween(scheduler, zone, day, 19, 24)
                                + occupantMinutesBetween(scheduler, zone, day, 0, 6),
                        zone + " must be empty overnight on " + day))));
    }

    @Test
    @DisplayName("T-07: no room is ever occupied beyond the seats ADR-0008 gives it")
    void shouldNeverExceedTheRoomsDesignOccupancy() {
        OccupancyScheduler scheduler = new OccupancyScheduler(42);

        assertAll(
                () -> assertEquals(1, peakOverTheWeek(scheduler, Topology.A125A),
                        "A125A is a single office"),
                () -> assertEquals(2, peakOverTheWeek(scheduler, Topology.A125),
                        "A125 holds two desks"),
                () -> assertEquals(4, peakOverTheWeek(scheduler, Topology.A116A),
                        "A116A takes three or four people"),
                () -> assertTrue(peakOverTheWeek(scheduler, Topology.A115) <= 10,
                        "A115 has ten seats"),
                () -> assertTrue(peakOverTheWeek(scheduler, Topology.A124) <= 8,
                        "A124 has eight desks"),
                () -> assertTrue(peakOverTheWeek(scheduler, Topology.A145) <= 20,
                        "A145 has twenty seats"));
    }

    @Test
    @DisplayName("T-07: the six schedules differ — the seminar room is not the single office")
    void shouldGiveEachRoomTypeItsOwnShape() {
        OccupancyScheduler scheduler = new OccupancyScheduler(42);

        int singleOfficeMinutes = occupantMinutes(scheduler, Topology.A125A, MONDAY);
        int openPlanMinutes = occupantMinutes(scheduler, Topology.A124, MONDAY);

        assertAll(
                () -> assertTrue(singleOfficeMinutes > 60 * 7,
                        "the single office is occupied most of the day, was: " + singleOfficeMinutes),
                () -> assertTrue(openPlanMinutes > singleOfficeMinutes,
                        "eight desks produce more occupant minutes than one"),
                () -> assertTrue(peakOverTheWeek(scheduler, Topology.A145) > 1,
                        "the seminar room fills in bursts"),
                () -> assertTrue(bookedHours(scheduler, Topology.A145) % 120 == 0,
                        "and it books whole two-hour slots"));
    }

    @Test
    @DisplayName("R-3: the same seed gives the same week whatever order the rooms are asked in")
    void shouldDrawTheSameWeekForTheSameSeedRegardlessOfOrder() {
        OccupancyScheduler forwards = new OccupancyScheduler(42);
        OccupancyScheduler backwards = new OccupancyScheduler(42);
        List<ZoneId> reversed = new ArrayList<>(Topology.ZONE_IDS).reversed();
        reversed.forEach(zone -> weekdays().reversed()
                .forEach(day -> occupantMinutes(backwards, zone, day)));

        assertEquals(weekAsRead(forwards), weekAsRead(backwards),
                "the draw depends on the room and the day alone, not on who asked first");
    }

    @Test
    @DisplayName("T-07: a different seed gives a different week")
    void shouldDrawADifferentWeekForADifferentSeed() {
        int withDefaultSeed = occupantMinutes(new OccupancyScheduler(42), Topology.A115, MONDAY);
        int withOtherSeed = occupantMinutes(new OccupancyScheduler(7), Topology.A115, MONDAY);

        assertTrue(withDefaultSeed != withOtherSeed,
                "seeds 42 and 7 must not book the meeting room the same way");
    }

    @Test
    @DisplayName("T-07: the single office empties over lunch")
    void shouldEmptyTheSingleOfficeOverLunch() {
        OccupancyScheduler scheduler = new OccupancyScheduler(42);

        int lunchMinutes = 0;
        for (LocalDate day : weekdays()) {
            lunchMinutes += occupantMinutesBetween(scheduler, Topology.A125A, day, 11, 14);
        }

        assertTrue(lunchMinutes < weekdays().size() * 3 * 60,
                "a lunch dip means the office is not occupied for every one of those minutes");
    }

    private static List<Integer> weekAsRead(OccupancyScheduler scheduler) {
        List<Integer> week = new ArrayList<>();
        Topology.ZONE_IDS.forEach(zone -> weekdays()
                .forEach(day -> week.add(occupantMinutes(scheduler, zone, day))));
        return week;
    }

    private static List<LocalDate> weekdays() {
        List<LocalDate> days = new ArrayList<>();
        for (int offset = 0; offset < 5; offset++) {
            days.add(MONDAY.plusDays(offset));
        }
        return days;
    }

    private static int peakOverTheWeek(OccupancyScheduler scheduler, ZoneId zone) {
        int peak = 0;
        for (LocalDate day : weekdays()) {
            for (int minute = 0; minute < Duration.ofDays(1).toMinutes(); minute++) {
                peak = Math.max(peak, scheduler.headCount(zone, at(day, minute)));
            }
        }
        return peak;
    }

    private static int bookedHours(OccupancyScheduler scheduler, ZoneId zone) {
        int minutes = 0;
        for (LocalDate day : weekdays()) {
            for (int minute = 0; minute < Duration.ofDays(1).toMinutes(); minute++) {
                if (scheduler.headCount(zone, at(day, minute)) > 0) {
                    minutes++;
                }
            }
        }
        return minutes;
    }

    private static int occupantMinutes(OccupancyScheduler scheduler, ZoneId zone, LocalDate day) {
        return occupantMinutesBetween(scheduler, zone, day, 0, 24);
    }

    private static int occupantMinutesBetween(
            OccupancyScheduler scheduler, ZoneId zone, LocalDate day, int fromHour, int toHour) {
        int occupied = 0;
        for (int minute = fromHour * 60; minute < toHour * 60; minute++) {
            occupied += scheduler.headCount(zone, at(day, minute));
        }
        return occupied;
    }

    private static Instant at(LocalDate day, int minuteOfDay) {
        return LocalDateTime.of(day, java.time.LocalTime.MIDNIGHT)
                .plusMinutes(minuteOfDay).toInstant(ZoneOffset.UTC);
    }

    /**
     * The schedules dispatch on {@code Topology.templateOf} rather than on the zone's own identity,
     * so a zone the topology built by copying one of the six is occupied as the room it copied. This
     * asserts the property that holds for any configured zone set: every zone the process runs has a
     * schedule. The regression it stands for is a 12-zone container that threw "no schedule for zone
     * A105" on its first tick, which no six-zone JVM can reproduce because the count is read once per
     * process.
     */
    @Test
    @DisplayName("R-6: every zone this process is configured with has a schedule")
    void shouldScheduleEveryConfiguredZone() {
        OccupancyScheduler scheduler = new OccupancyScheduler(42);
        Instant midMorning = LocalDateTime.of(MONDAY, java.time.LocalTime.of(10, 0))
                .toInstant(ZoneOffset.UTC);

        assertEquals(Topology.ZONE_IDS.size(), scheduler.headCounts(midMorning).size());
        for (ZoneId zone : Topology.ZONE_IDS) {
            assertTrue(scheduler.headCount(zone, midMorning) >= 0,
                    zone + " has no schedule, so the simulator cannot integrate it");
        }
    }
}
