package se.ltu.d7065e.shared.boundary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import se.ltu.d7065e.shared.control.ServiceStatePort;

/**
 * {@link ServiceStatePort} as one file on the service's own bind-mounted volume (ADR-0012).
 *
 * <p>A file rather than a database because the access pattern is one whole-object write on change
 * and one whole-object read at boot, by a single writer, with no query. {@code mosquitto} in this
 * same deployment already keeps its session state this way under {@code storage/mosquitto/data};
 * this is that pattern, one service along.
 *
 * <h2>Why the write is a move and not a write</h2>
 *
 * <p>The failure this class exists to survive is a process dying at an arbitrary instant, and a
 * process that dies halfway through rewriting its own state file would come back to a truncated
 * document — a worse outcome than the cold start it was trying to avoid, because the truncation is
 * silent and the document looks present. So the document is written to a temporary file in the
 * <em>same directory</em>, which is what makes the subsequent move a rename within one filesystem
 * rather than a copy, and the rename is atomic: a reader sees the old document or the new one and
 * never a half of either.
 *
 * <p>{@link StandardCopyOption#ATOMIC_MOVE} is requested and not merely hoped for. Where a
 * filesystem cannot honour it the move falls back to a replacing one, which is the best available
 * and is the case on no deployment target this project runs on.
 */
public final class FileServiceStateAdapter implements ServiceStatePort {

    private static final String NOTHING = "";

    private final Path document;
    private final Path pending;

    public FileServiceStateAdapter(Path document) {
        this.document = document;
        this.pending = document.resolveSibling(document.getFileName() + ".pending");
    }

    @Override
    public void save(String state) {
        try {
            Path directory = document.getParent();
            if (directory != null) {
                Files.createDirectories(directory);
            }
            Files.writeString(pending, state, StandardCharsets.UTF_8);
            moveIntoPlace();
        } catch (IOException cannotPersist) {
            throw new IllegalStateException("cannot write service state to " + document,
                    cannotPersist);
        }
    }

    @Override
    public String load() {
        try {
            return Files.exists(document)
                    ? Files.readString(document, StandardCharsets.UTF_8)
                    : NOTHING;
        } catch (IOException unreadable) {
            return NOTHING;
        }
    }

    private void moveIntoPlace() throws IOException {
        try {
            Files.move(pending, document,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomicHere) {
            Files.move(pending, document, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
