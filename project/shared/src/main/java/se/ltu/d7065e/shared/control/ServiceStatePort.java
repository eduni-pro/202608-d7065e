package se.ltu.d7065e.shared.control;

/**
 * Durable storage for what one service currently believes, so a restart resumes instead of
 * starting cold (ADR-0012).
 *
 * <p>This is deliberately not the journal. {@link ResidualJournalPort} is an append-only record of
 * <em>what happened</em>, queried over time windows and never rewritten, because R-5 has to
 * reconstruct a diagnosis from it long afterwards. This port holds <em>what is believed now</em>:
 * one document per service, replaced whole on every change, read once at boot and never queried.
 * The two have different lifetimes and different readers, and keeping them apart is what stops a
 * schema change made for recovery landing in the hypertables a filled requirement rests on.
 *
 * <p>The document is a string rather than a parsed structure because meaning is the caller's
 * concern and durable bytes are the adapter's — the same division {@code EventCodec} already draws
 * for the wire. A service's {@code boundary} builds the document from a snapshot its control layer
 * published and parses it back the same way, so no {@code control} or {@code entity} class ever
 * learns how the state is stored.
 */
public interface ServiceStatePort {

    /**
     * Replaces the stored document. Atomic: a crash leaves either the old document or the new one,
     * never a part of either.
     *
     * <p>Throws when the document cannot be written, rather than failing quietly. A caller that
     * would rather keep running than persist is the right place to decide that, and it can only
     * decide it if it is told — a state store that silently stops storing is the failure this
     * whole record exists to avoid, one level up.
     */
    void save(String document);

    /**
     * The stored document, or {@code ""} when there is nothing to resume from.
     *
     * <p>Absent and unreadable are deliberately the same answer. A service that cannot read its
     * last state starts cold, which is exactly what it did before this port existed, so a damaged
     * document degrades the system to its previous behaviour rather than failing a boot.
     */
    String load();
}
