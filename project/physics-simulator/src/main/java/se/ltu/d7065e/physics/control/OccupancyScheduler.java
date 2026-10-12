package se.ltu.d7065e.physics.control;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * Head count per room and minute, one schedule per room type as ADR-0008 Part 1 describes
 * them: a single office, a two-person office, a project room, a meeting room, an open-plan
 * office and a seminar room.
 *
 * <p>A zone the topology created by copying one of those six — which is what {@code ZONE_COUNT}
 * above six produces for R-6 — is scheduled as the room it was copied from, and still draws its own
 * day, because the draw is seeded from the zone's own name.
 *
 * <p>A day is drawn rather than fixed, so no two days are alike, but the draw is seeded from
 * the room and the calendar day alone. That makes a run reproducible regardless of the order
 * in which rooms are asked — which R-3's seven-day replay and the fixed-seed confusion matrix
 * (T-15) both need.
 *
 * <p>Weekends are empty, and every schedule ends by 18:00, so the night decays every room to
 * outdoor concentration. That decay is the classifier's reference condition for sensor drift,
 * so nothing here holds a room occupied around the clock.
 *
 * <p>Local time is UTC throughout: the building runs on one offset in simulation and a
 * daylight-saving jump would only make a replay harder to explain.
 */
public final class OccupancyScheduler implements OccupancySource {

    private static final int MINUTES_PER_DAY = 24 * 60;

    private final long seed;
    private final Map<DayPlanKey, int[]> plans = new HashMap<>();

    public OccupancyScheduler(long seed) {
        this.seed = seed;
    }

    /** Head count in every room at this instant, in the topology's room order. */
    @Override
    public Map<ZoneId, Integer> headCounts(Instant at) {
        Map<ZoneId, Integer> counts = new LinkedHashMap<>();
        for (ZoneId zone : Topology.ZONE_IDS) {
            counts.put(zone, headCount(zone, at));
        }
        return counts;
    }

    public int headCount(ZoneId zone, Instant at) {
        LocalDateTime local = LocalDateTime.ofInstant(at, ZoneOffset.UTC);
        return dayPlan(zone, local.toLocalDate())[local.getHour() * 60 + local.getMinute()];
    }

    private int[] dayPlan(ZoneId zone, LocalDate day) {
        return plans.computeIfAbsent(new DayPlanKey(zone, day), key -> generate(key.zone(), key.day()));
    }

    private int[] generate(ZoneId zone, LocalDate day) {
        int[] minutes = new int[MINUTES_PER_DAY];
        DayOfWeek dayOfWeek = day.getDayOfWeek();
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return minutes;
        }
        Random random = dayRandom(zone, day);
        // The room type follows the geometry: a zone the topology built as a copy of the seminar
        // room is occupied like a seminar room. For the six real rooms the template is the room
        // itself, so this is the same dispatch it always was.
        ZoneId type = Topology.templateOf(zone);
        if (type.equals(Topology.A125A)) {
            singleOffice(minutes, random);
        } else if (type.equals(Topology.A125)) {
            sharedOffice(minutes, random, 2);
        } else if (type.equals(Topology.A116A)) {
            projectRoom(minutes, random);
        } else if (type.equals(Topology.A115)) {
            meetingRoom(minutes, random);
        } else if (type.equals(Topology.A124)) {
            openPlanOffice(minutes, random, 8);
        } else if (type.equals(Topology.A145)) {
            seminarRoom(minutes, random);
        } else {
            throw new IllegalArgumentException("no schedule for zone " + zone);
        }
        return minutes;
    }

    /**
     * Seeded from the room and the day rather than from a shared stream, so asking for one
     * room's Tuesday does not change another room's Monday.
     */
    private Random dayRandom(ZoneId zone, LocalDate day) {
        return new Random(seed * 1_000_003L + day.toEpochDay() * 9_176_897L + zone.value().hashCode());
    }

    /** A125A: one person, 08-17, away over lunch. */
    private static void singleOffice(int[] minutes, Random random) {
        int arrival = minute(7, 50) + random.nextInt(41);
        int departure = minute(16, 45) + random.nextInt(31);
        occupy(minutes, arrival, departure, 1);
        lunchDip(minutes, random);
    }

    /** A125: two desks, each arriving and leaving on its own, so the room fills in steps. */
    private static void sharedOffice(int[] minutes, Random random, int desks) {
        for (int desk = 0; desk < desks; desk++) {
            addDeskWorker(minutes, random);
        }
    }

    /** A124: eight desks, five to eight of them used, continuous through the working day. */
    private static void openPlanOffice(int[] minutes, Random random, int desks) {
        int used = desks - 3 + random.nextInt(4);
        for (int desk = 0; desk < used; desk++) {
            addDeskWorker(minutes, random);
        }
    }

    /** A116A: two or three working sessions of 45-120 minutes with 3-4 people, empty between. */
    private static void projectRoom(int[] minutes, Random random) {
        int cursor = minute(9, 0);
        int sessions = 2 + random.nextInt(2);
        for (int session = 0; session < sessions && cursor < minute(16, 0); session++) {
            int start = cursor + random.nextInt(60);
            int end = Math.min(start + 45 + random.nextInt(76), minute(17, 0));
            occupy(minutes, start, end, 3 + random.nextInt(2));
            cursor = end + 30 + random.nextInt(60);
        }
    }

    /** A115: ten seats booked in whole-hour slots between 08:00 and 17:00. */
    private static void meetingRoom(int[] minutes, Random random) {
        for (int hour = 8; hour < 17; hour++) {
            if (random.nextDouble() < 0.35) {
                occupy(minutes, minute(hour, 0), minute(hour + 1, 0), 4 + random.nextInt(7));
            }
        }
    }

    /** A145: twenty seats booked in two-hour slots, and empty between them. */
    private static void seminarRoom(int[] minutes, Random random) {
        for (int hour : new int[] {8, 10, 13, 15}) {
            if (random.nextDouble() < 0.3) {
                occupy(minutes, minute(hour, 0), minute(hour + 2, 0), 8 + random.nextInt(13));
            }
        }
    }

    /** One desk worker: in 07:30-09:00, out 16:00-18:00, away 30-60 minutes over lunch. */
    private static void addDeskWorker(int[] minutes, Random random) {
        int arrival = minute(7, 30) + random.nextInt(91);
        int departure = minute(16, 0) + random.nextInt(121);
        occupy(minutes, arrival, departure, 1);
        lunchDip(minutes, random);
    }

    private static void lunchDip(int[] minutes, Random random) {
        int start = minute(11, 30) + random.nextInt(61);
        occupy(minutes, start, start + 30 + random.nextInt(31), -1);
    }

    private static void occupy(int[] minutes, int from, int to, int delta) {
        for (int minute = Math.max(0, from); minute < Math.min(MINUTES_PER_DAY, to); minute++) {
            minutes[minute] += delta;
        }
    }

    private static int minute(int hour, int minute) {
        return hour * 60 + minute;
    }

    private record DayPlanKey(ZoneId zone, LocalDate day) {
    }
}
