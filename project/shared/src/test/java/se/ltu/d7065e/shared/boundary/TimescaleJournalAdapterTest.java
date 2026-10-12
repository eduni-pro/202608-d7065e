package se.ltu.d7065e.shared.boundary;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * What can be asserted about the journal adapter with no PostgreSQL in the room.
 *
 * <p>Every statement in {@link TimescaleJournalAdapter} — the seven inserts, the per-zone window
 * scans, the case window, and the one-hour evidence lookback that decides which residuals belong to
 * a case — needs a live database to execute, and the dialect is PostgreSQL's (a {@code ?::jsonb}
 * cast, {@code BETWEEN} over {@code timestamptz}), so no in-memory substitute would exercise the
 * same SQL. That is what {@link TimescaleJournalIntegrationTest} is for: it runs the adapter's own
 * statements against the compose stack's Timescale container and skips when there is none, which is
 * where R-5's claim now comes from. {@code CaseReplayTest} in the dashboard covers the part of the
 * replay that is not SQL: ordering, the empty case, and the unconfigured journal.
 *
 * <p>What is verified below is the one contract the adapter's two callers rely on without a
 * database: that an unusable JDBC URL fails the constructor instead of yielding a half-built
 * adapter. {@code DashboardRuntime} and {@code DamperControlJob} both catch that failure and carry
 * on with case replay disabled, which is only safe if it is a failure.
 */
class TimescaleJournalAdapterTest {

    @Test
    void shouldRefuseToOpenAJournalNoDriverCanServe() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new TimescaleJournalAdapter("jdbc:nosuchdatabase://nowhere/journal",
                        "user", "password"));

        assertTrue(thrown.getMessage().contains("jdbc:nosuchdatabase://nowhere/journal"),
                "the message names the URL that failed, or the operator cannot act on it: "
                        + thrown.getMessage());
    }
}
