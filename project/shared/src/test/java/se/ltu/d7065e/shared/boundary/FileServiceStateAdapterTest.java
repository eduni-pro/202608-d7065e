package se.ltu.d7065e.shared.boundary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.ltu.d7065e.shared.control.ServiceStatePort;

/**
 * The durability contract of {@link FileServiceStateAdapter} (ADR-0012), which is the whole reason
 * the class is not two lines of {@code Files.writeString} and {@code Files.readString}.
 */
@DisplayName("Service state on a file")
class FileServiceStateAdapterTest {

    @TempDir
    Path volume;

    @Test
    void shouldReportNothingWhenNoStateHasEverBeenSaved() {
        assertEquals("", adapter().load(),
                "a service with no saved state must start cold rather than fail");
    }

    @Test
    void shouldReturnTheDocumentThatWasSaved() {
        ServiceStatePort state = adapter();

        state.save("{\"zone\":\"A115\",\"latched\":true}");

        assertEquals("{\"zone\":\"A115\",\"latched\":true}", state.load());
    }

    @Test
    void shouldKeepOnlyTheNewestDocument() {
        ServiceStatePort state = adapter();
        state.save("{\"cycle\":1}");

        state.save("{\"cycle\":2}");

        assertEquals("{\"cycle\":2}", state.load(),
                "state is replaced whole, never appended — history is the journal's job");
    }

    @Test
    void shouldCreateTheDirectoryWhenTheVolumeIsEmpty() {
        Path nested = volume.resolve("fault-monitor").resolve("state.json");

        new FileServiceStateAdapter(nested).save("{}");

        assertTrue(Files.exists(nested),
                "a first boot on a fresh volume must not need the directory made for it");
    }

    @Test
    void shouldLeaveNoTemporaryFileBehindAfterSaving() throws IOException {
        adapter().save("{\"cycle\":1}");

        try (Stream<Path> files = Files.list(volume)) {
            List<String> names = files.map(path -> path.getFileName().toString()).toList();
            assertEquals(List.of("state.json"), names,
                    "a leftover pending file is a half-written document waiting to be mistaken "
                            + "for a whole one");
        }
    }

    @Test
    void shouldStartColdWhenTheDocumentCannotBeRead() throws IOException {
        Path directoryWhereAFileShouldBe = volume.resolve("state.json");
        Files.createDirectory(directoryWhereAFileShouldBe);

        assertEquals("", adapter().load(),
                "unreadable and absent are the same answer: the service degrades to the behaviour "
                        + "it had before it could persist anything");
    }

    @Test
    void shouldNotLoseTheOldDocumentWhenTheNewOneIsIdentical() {
        ServiceStatePort state = adapter();
        state.save("{\"cycle\":1}");

        state.save("{\"cycle\":1}");

        assertEquals("{\"cycle\":1}", state.load());
        assertFalse(state.load().isEmpty(), "a no-op save must not empty the document");
    }

    @Test
    void shouldRoundTripTheNonAsciiAnExplanatoryStateFieldMayCarry() {
        ServiceStatePort state = adapter();

        state.save("{\"reason\":\"residual 134 ppm, CO₂ above 1000 ppm\"}");

        assertEquals("{\"reason\":\"residual 134 ppm, CO₂ above 1000 ppm\"}", state.load(),
                "the document is written and read as UTF-8 rather than the platform default");
    }

    private ServiceStatePort adapter() {
        return new FileServiceStateAdapter(volume.resolve("state.json"));
    }

    @Test
    void shouldWriteTheDocumentAsUtf8BytesOnDisk() throws IOException {
        adapter().save("{\"reason\":\"CO₂\"}");

        assertEquals("{\"reason\":\"CO₂\"}",
                new String(Files.readAllBytes(volume.resolve("state.json")),
                        StandardCharsets.UTF_8),
                "so a document written on one machine reads the same on another");
    }
}
