package se.ltu.d7065e.physics.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.physics.entity.FaultKind;
import se.ltu.d7065e.physics.entity.InjectedFault;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * The fault list has two users on two threads: the tick loop reads it several times per simulated
 * second on the main thread, and {@code FaultControlSurface} adds to it and removes from it on an
 * HTTP handler thread whenever an operator curls it. These are the tests for that, and for the
 * removal the operator's DELETE performs.
 */
class ConcurrentFaultMutationTest {

    private static final Instant NOW =
            LocalDateTime.of(2026, 10, 9, 8, 0).toInstant(ZoneOffset.UTC);
    private static final int PRELOADED_FAULTS = 60;
    private static final int ROUNDS = 2000;

    /**
     * No sleeps and no timing assumptions: both threads do a fixed amount of work and the test
     * ends when both have finished. It cannot flake green-to-red either, because a copy-on-write
     * list's iteration is <em>unconditionally</em> safe against concurrent structural change —
     * there is no window to lose. What it catches is the regression: against a plain
     * {@code ArrayList} the reader below throws {@code ConcurrentModificationException} within
     * the first few rounds, every run, which is exactly what an operator injecting a fault
     * mid-tick used to do to the simulator's main thread.
     */
    @Test
    @DisplayName("the tick loop's reads survive an operator injecting from another thread")
    void shouldKeepReadingTheFaultListWhileAnotherThreadMutatesIt() throws Exception {
        FaultInjector injector = new FaultInjector();
        for (int fault = 0; fault < PRELOADED_FAULTS; fault++) {
            ZoneId zone = Topology.ZONE_IDS.get(fault % Topology.ZONE_IDS.size());
            injector.inject(InjectedFault.sensorDrift(zone, 10 + fault, NOW));
        }
        AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        CyclicBarrier bothRunning = new CyclicBarrier(2);

        Thread reader = new Thread(() -> {
            try {
                bothRunning.await();
                for (int round = 0; round < ROUNDS; round++) {
                    for (ZoneId zone : Topology.ZONE_IDS) {
                        injector.appliedPosition(zone, DamperPosition.OPEN, NOW);
                        injector.sensorErrorPpm(zone, NOW);
                    }
                    injector.filterBlockage(NOW);
                    injector.activeAt(NOW);
                }
            } catch (Throwable failure) {
                readerFailure.set(failure);
            }
        }, "tick-loop");
        Thread writer = new Thread(() -> {
            try {
                bothRunning.await();
                for (int round = 0; round < ROUNDS; round++) {
                    injector.inject(InjectedFault.sensorOffset(Topology.A124, 100 + round, NOW));
                    injector.remove(FaultKind.SENSOR_OFFSET, Topology.A124);
                }
            } catch (Throwable ignored) {
                // The reader is what this test is about; a writer failure would surface as a
                // reader failure or as the count assertion below.
            }
        }, "fault-control");

        reader.start();
        writer.start();
        reader.join();
        writer.join();

        assertNull(readerFailure.get(), () -> "a read mid-injection threw " + readerFailure.get());
        assertEquals(PRELOADED_FAULTS, injector.scheduled().size(),
                "every injected fault was removed again, so the list is back where it started");
    }

    @Test
    @DisplayName("clearing one room's fault leaves the other rooms' faults biting")
    void shouldRemoveOnlyTheFaultsOnTheNamedTarget() {
        FaultInjector injector = new FaultInjector();
        injector.inject(
                InjectedFault.stuckDamper(Topology.A115, Topology.MINIMUM_POSITION, NOW));
        injector.inject(InjectedFault.stuckDamper(Topology.A124, DamperPosition.of(0.2), NOW));
        injector.inject(InjectedFault.blockedFilter(0.4, NOW));

        int removed = injector.remove(FaultKind.STUCK_DAMPER, Topology.A115);

        assertEquals(1, removed);
        assertEquals(DamperPosition.OPEN,
                injector.appliedPosition(Topology.A115, DamperPosition.OPEN, NOW),
                "A115's damper obeys its command again, which is the repair");
        assertEquals(0.2, injector.appliedPosition(Topology.A124, DamperPosition.OPEN, NOW).value(),
                1e-12, "A124 is still stuck, because nobody repaired it");
        assertEquals(0.4, injector.filterBlockage(NOW), 1e-12, "and the filter is still blocked");
    }

    @Test
    @DisplayName("a plant-wide fault is cleared by naming no room at all")
    void shouldRemoveAPlantWideFaultWithoutAZone() {
        FaultInjector injector = new FaultInjector();
        injector.inject(InjectedFault.blockedFilter(0.4, NOW));
        injector.inject(
                InjectedFault.stuckDamper(Topology.A115, Topology.MINIMUM_POSITION, NOW));

        int removed = injector.remove(FaultKind.BLOCKED_FILTER, null);

        assertEquals(1, removed);
        assertEquals(0, injector.filterBlockage(NOW), 1e-12);
        assertEquals(1, injector.scheduled().size(), "the stuck damper is untouched");
    }
}
