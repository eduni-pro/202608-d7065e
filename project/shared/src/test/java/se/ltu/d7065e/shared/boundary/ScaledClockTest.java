package se.ltu.d7065e.shared.boundary;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The demo clock: simulated time running faster than the clock that paces it.
 *
 * <p>{@code SimulatedClock} stands in for real time here. It advances by whatever it is asked to
 * wait and returns immediately, so it both avoids sleeping in a unit test and records exactly how
 * much real time the scaled clock asked for.
 */
class ScaledClockTest {

    private static final Instant FIRST_TICK =
            LocalDateTime.of(2026, 10, 9, 7, 45).toInstant(ZoneOffset.UTC);
    private static final Instant REAL_START = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("START_AT: the first tick reports exactly the requested instant")
    void shouldReportTheRequestedInstantOnTheFirstTick() throws InterruptedException {
        Duration tick = Duration.ofSeconds(60);
        ScaledClock clock = new ScaledClock(new SimulatedClock(REAL_START), FIRST_TICK, tick, 60);

        clock.waitFor(tick);

        assertEquals(FIRST_TICK, clock.now(),
                "the simulator reads the clock after waiting, so the first read is START_AT");
    }

    @Test
    @DisplayName("TIME_SCALE=60: a 60 s simulated span costs one real second")
    void shouldSpendOneRealSecondOnSixtySimulatedOnes() throws InterruptedException {
        SimulatedClock realTime = new SimulatedClock(REAL_START);
        Duration tick = Duration.ofSeconds(60);
        ScaledClock clock = new ScaledClock(realTime, FIRST_TICK, tick, 60);

        clock.waitFor(tick);
        clock.waitFor(tick);

        assertAll(
                () -> assertEquals(FIRST_TICK.plusSeconds(60), clock.now(),
                        "two ticks of 60 simulated seconds advance simulated time by 120 s"),
                () -> assertEquals(REAL_START.plusSeconds(2), realTime.now(),
                        "and cost two real seconds"));
    }

    @Test
    @DisplayName("TIME_SCALE=1: simulated time and real time advance together")
    void shouldAdvanceSimulatedAndRealTimeTogetherAtScaleOne() throws InterruptedException {
        SimulatedClock realTime = new SimulatedClock(REAL_START);
        Duration tick = Duration.ofSeconds(1);
        ScaledClock clock = new ScaledClock(realTime, FIRST_TICK, tick, 1);

        for (int tickIndex = 0; tickIndex < 300; tickIndex++) {
            clock.waitFor(tick);
        }

        assertAll(
                () -> assertEquals(FIRST_TICK.plusSeconds(299), clock.now()),
                () -> assertEquals(REAL_START.plusSeconds(300), realTime.now(),
                        "at scale 1 a second of plant costs a second of wall clock, as before"));
    }

    @Test
    @DisplayName("REAL_EPOCH: two clocks built at different real instants report the same time")
    void shouldAgreeBetweenTwoClocksBuiltAtDifferentRealInstants() throws InterruptedException {
        // One real timeline, two processes joining it twenty real seconds apart. Both are handed
        // the same START_AT and the same REAL_EPOCH, which is the whole of the contract.
        SimulatedClock realTime = new SimulatedClock(REAL_START);
        ScaledClock early = new ScaledClock(realTime, FIRST_TICK, REAL_START, 60);
        realTime.advance(Duration.ofSeconds(20));
        ScaledClock late = new ScaledClock(realTime, FIRST_TICK, REAL_START, 60);

        assertAll(
                () -> assertEquals(early.now(), late.now(),
                        "a process that started later must not be on a timeline of its own"),
                () -> assertEquals(FIRST_TICK.plusSeconds(20 * 60), late.now(),
                        "and both must read START_AT plus the scaled real elapsed time"));

        realTime.advance(Duration.ofSeconds(5));

        assertEquals(early.now(), late.now(), "and they must stay together as real time passes");
    }

    @Test
    @DisplayName("a clock constructed later reports the same simulated time as one constructed earlier")
    void shouldStayInStepAcrossRestarts() throws InterruptedException {
        // The restart case: a container dies after a minute of demo and comes back. Its new clock
        // must rejoin the timeline the survivors are on rather than restart the scenario.
        SimulatedClock realTime = new SimulatedClock(REAL_START);
        ScaledClock survived = new ScaledClock(realTime, FIRST_TICK, REAL_START, 60);
        for (int realSecond = 0; realSecond < 60; realSecond++) {
            survived.waitFor(Duration.ofSeconds(60));
        }
        ScaledClock restarted = new ScaledClock(realTime, FIRST_TICK, REAL_START, 60);

        assertAll(
                () -> assertEquals(survived.now(), restarted.now(),
                        "a restarted container rejoins the timeline instead of starting a second"),
                () -> assertEquals(FIRST_TICK.plusSeconds(3600), restarted.now(),
                        "an hour of simulated time after one real minute at 60x"));
    }

    @Test
    @DisplayName("TIME_SCALE=1 anchored on its own epoch is the wall clock, instant for instant")
    void shouldBeTheWallClockAtScaleOne() throws InterruptedException {
        SimulatedClock realTime = new SimulatedClock(REAL_START);
        ScaledClock clock = new ScaledClock(realTime, REAL_START, REAL_START, 1);

        assertEquals(realTime.now(), clock.now(), "START_AT = REAL_EPOCH at scale 1 is identity");
        for (int step = 0; step < 100; step++) {
            clock.waitFor(Duration.ofSeconds(1));
            assertEquals(realTime.now(), clock.now(),
                    "and it stays the wall clock after " + step + " seconds");
        }
        assertEquals(REAL_START.plusSeconds(100), realTime.now(),
                "a second of simulated time costs a second of real time at scale 1");
    }

    @Test
    @DisplayName("pacing absorbs the time a caller spends between waits instead of accumulating it")
    void shouldPaceTowardsAnAbsoluteDeadline() throws InterruptedException {
        SimulatedClock realTime = new SimulatedClock(REAL_START);
        Duration tick = Duration.ofSeconds(60);
        ScaledClock clock = new ScaledClock(realTime, FIRST_TICK, REAL_START, 60);

        clock.waitFor(tick);
        // 200 ms of BuildSim round trip, which a relative sleep would turn into permanent lag.
        realTime.advance(Duration.ofMillis(200));
        clock.waitFor(tick);

        assertAll(
                () -> assertEquals(REAL_START.plusSeconds(2), realTime.now(),
                        "two ticks still cost exactly two real seconds"),
                () -> assertEquals(FIRST_TICK.plusSeconds(120), clock.now(),
                        "so simulated time is still exactly on the tick grid"));
    }

    @Test
    @DisplayName("a caller more than one span behind does not sleep, so it traverses its backlog")
    void shouldNotSleepWhenAlreadyBehind() throws InterruptedException {
        SimulatedClock realTime = new SimulatedClock(REAL_START);
        ScaledClock clock = new ScaledClock(realTime, FIRST_TICK, REAL_START, 60);
        // Ten real seconds of container start-up: ten simulated minutes the plant has to cover.
        realTime.advance(Duration.ofSeconds(10));

        clock.waitFor(Duration.ofSeconds(60));

        assertEquals(REAL_START.plusSeconds(10), realTime.now(),
                "the first tick of the backlog is taken immediately rather than skipped");
    }

    @Test
    @DisplayName("TIME_SCALE below 1 would run the plant slower than real time and is refused")
    void shouldRefuseATimeScaleBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> new ScaledClock(
                new SimulatedClock(REAL_START), FIRST_TICK, Duration.ofSeconds(1), 0));
    }
}
