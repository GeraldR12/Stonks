package me.geraldr12.shadow;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ObservationWriterRuntimeFailureTest {

    @Test
    void runtimeFailureIsCountedAndWarnedOncePerCycle() {
        List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        ObservationWriter writer = new ObservationWriter(Path.of("unused"), warnings::add, 10, 1024, new ObservationWriter.Sink() {
            @Override
            public long size(Path path) {
                return 0;
            }

            @Override
            public void append(Path path, byte[] bytes) throws IOException {
                throw new IllegalStateException("broken sink");
            }
        });

        writer.submit(Instant.EPOCH, 1, "{}");
        writer.submit(Instant.EPOCH, 1, "{}");
        writer.close();

        assertEquals(2, writer.dropped());
        assertEquals(1, warnings.size());
    }
}
