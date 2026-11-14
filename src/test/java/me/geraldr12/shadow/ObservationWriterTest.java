package me.geraldr12.shadow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ObservationWriterTest {
    private static final Instant DAY = Instant.parse("2026-01-02T23:59:59Z");
    @TempDir Path root;

    @Test
    void constructionAndEmptyCloseNeverCreateDirectory() {
        Path directory = root.resolve("lazy");
        try (ObservationWriter writer = new ObservationWriter(directory, message -> fail(message))) {
            assertFalse(Files.exists(directory));
            assertEquals(0, writer.dropped());
        }
        assertFalse(Files.exists(directory));
    }

    @Test
    void closeDrainsUtf8LinesToObservedUtcDayAndPreservesOldFiles() throws Exception {
        Path directory = root.resolve("lazy");
        String json = "{\"name\":\"São \\\"A\\\"\\\\B\\nC\"}";
        try (ObservationWriter writer = new ObservationWriter(directory, message -> fail(message))) {
            assertTrue(writer.submit(DAY, 1, json));
            assertTrue(writer.submit(DAY.plusSeconds(1), 1, "{\"next\":true}"));
            assertTrue(writer.submit(DAY, 2, "{}"));
        }
        assertEquals(json + "\n{}\n", Files.readString(file(directory, "2026-01-02")));
        assertEquals("{\"next\":true}\n", Files.readString(file(directory, "2026-01-03")));
        try (ObservationWriter writer = new ObservationWriter(directory, message -> fail(message))) {
            assertTrue(writer.submit(DAY, 3, "{\"append\":true}"));
        }
        assertEquals(json + "\n{}\n{\"append\":true}\n",
                Files.readString(file(directory, "2026-01-02")));
    }

    @Test
    void workerIsSingleAndNotCallerThreadAndDefaultQueueDropsWhenFull() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicInteger written = new AtomicInteger();
        ObservationWriter.Sink sink = new ObservationWriter.Sink() {
            public long size(Path path) { return 0; }
            public void append(Path path, byte[] bytes) throws IOException {
                Thread previous = worker.getAndSet(Thread.currentThread());
                if (previous != null) assertSame(previous, Thread.currentThread());
                entered.countDown();
                try {
                    if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("test timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                }
                written.incrementAndGet();
            }
        };
        ObservationWriter writer = new ObservationWriter(root, message -> fail(message),
                10_000, 32L * 1024 * 1024, sink);
        try {
            assertTrue(writer.submit(DAY, 1, "{}"));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            for (int i = 0; i < 10_000; i++) assertTrue(writer.submit(DAY, 1, "{}"));
            assertFalse(writer.submit(DAY, 1, "{}"));
            assertEquals(1, writer.dropped());
            assertNotSame(Thread.currentThread(), worker.get());
        } finally {
            release.countDown();
            writer.close();
        }
        assertEquals(10_001, written.get());
        assertFalse(writer.submit(DAY, 2, "{}"));
        assertEquals(2, writer.dropped());
        writer.close();
    }

    @Test
    void ioFailureIsContainedAndWarnedOnceForEachCycleIncludingRevisitedCycle() {
        List<String> warnings = new CopyOnWriteArrayList<>();
        ObservationWriter.Sink sink = new ObservationWriter.Sink() {
            public long size(Path path) throws IOException { throw new IOException("unavailable"); }
            public void append(Path path, byte[] bytes) { fail("must not append after failed size"); }
        };
        try (ObservationWriter writer = new ObservationWriter(root, warnings::add, 20, 100, sink)) {
            for (long cycle : new long[]{1, 1, 2, 1, 2}) {
                assertTrue(writer.submit(DAY, cycle, "{}"));
            }
        }
        assertEquals(2, warnings.size());
        assertTrue(warnings.get(0).contains("1"));
        assertTrue(warnings.get(1).contains("2"));
        assertFalse(Files.exists(file(root, "2026-01-02")));
    }

    @Test
    void realFilesystemIoFailureDoesNotEscapeSubmitOrClose() throws Exception {
        Path notDirectory = root.resolve("file");
        Files.writeString(notDirectory, "keep");
        List<String> warnings = new CopyOnWriteArrayList<>();
        try (ObservationWriter writer = new ObservationWriter(notDirectory, warnings::add)) {
            assertTrue(writer.submit(DAY, 7, "{}"));
            assertTrue(writer.submit(DAY, 7, "{}"));
        }
        assertEquals(1, warnings.size());
        assertEquals("keep", Files.readString(notDirectory));
    }

    @Test
    void byteCapIncludesUtf8AndNewlineAndStopsDayEvenIfLaterLineFits() {
        List<String> warnings = new CopyOnWriteArrayList<>();
        List<String> output = new CopyOnWriteArrayList<>();
        AtomicInteger size = new AtomicInteger();
        ObservationWriter.Sink sink = new ObservationWriter.Sink() {
            public long size(Path path) { return size.get(); }
            public void append(Path path, byte[] bytes) {
                size.addAndGet(bytes.length);
                output.add(new String(bytes, StandardCharsets.UTF_8));
            }
        };
        try (ObservationWriter writer = new ObservationWriter(root, warnings::add, 20, 7, sink)) {
            assertTrue(writer.submit(DAY, 1, "é"));
            assertTrue(writer.submit(DAY, 1, "éé"));
            assertTrue(writer.submit(DAY, 2, "a"));
            assertTrue(writer.submit(DAY.plusSeconds(1), 3, "a"));
        }
        assertEquals(List.of("é\n", "a\n"), output);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("2026-01-02"));
    }

    @Test
    void actualDefaultLimitRespectsExistingFileAndWarnsOncePerUtcDay() throws Exception {
        long limit = 32L * 1024 * 1024;
        Path first = file(root, "2026-01-02");
        Path second = file(root, "2026-01-03");
        try (RandomAccessFile file = new RandomAccessFile(first.toFile(), "rw")) {
            file.setLength(limit - 3);
        }
        try (RandomAccessFile file = new RandomAccessFile(second.toFile(), "rw")) {
            file.setLength(limit);
        }
        List<String> warnings = new CopyOnWriteArrayList<>();
        try (ObservationWriter writer = new ObservationWriter(root, warnings::add)) {
            assertTrue(writer.submit(DAY, 1, "é"));
            assertTrue(writer.submit(DAY, 1, "{}"));
            assertTrue(writer.submit(DAY, 2, "{}"));
            assertTrue(writer.submit(DAY.plusSeconds(1), 2, "{}"));
            assertTrue(writer.submit(DAY.plusSeconds(1), 3, "{}"));
        }
        assertEquals(limit, Files.size(first));
        assertEquals(limit, Files.size(second));
        assertEquals(2, warnings.size());
        try (RandomAccessFile file = new RandomAccessFile(first.toFile(), "r")) {
            file.seek(limit - 3);
            byte[] end = new byte[3];
            file.readFully(end);
            assertArrayEquals("é\n".getBytes(StandardCharsets.UTF_8), end);
        }
    }

    @Test
    void throwingWarningConsumerDoesNotKillWorker() {
        AtomicInteger attempts = new AtomicInteger();
        ObservationWriter.Sink sink = new ObservationWriter.Sink() {
            public long size(Path path) throws IOException {
                attempts.incrementAndGet();
                throw new IOException("test");
            }
            public void append(Path path, byte[] bytes) { fail("unexpected append"); }
        };
        try (ObservationWriter writer = new ObservationWriter(root, warning -> {
            throw new IllegalStateException("logger unavailable");
        }, 20, 100, sink)) {
            assertTrue(writer.submit(DAY, 1, "{}"));
            assertTrue(writer.submit(DAY, 2, "{}"));
        }
        assertEquals(2, attempts.get());
    }

    @Test
    void shutdownInterruptsBlockedSinkAfterBoundedDrain() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        ObservationWriter.Sink sink = new ObservationWriter.Sink() {
            public long size(Path path) { return 0; }
            public void append(Path path, byte[] bytes) throws IOException {
                entered.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException exception) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                }
            }
        };
        ObservationWriter writer = new ObservationWriter(root, warning -> {}, 2, 100, sink);
        try {
            assertTrue(writer.submit(DAY, 1, "{}"));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(writer.submit(DAY, 1, "{}"));
            long start = System.nanoTime();
            writer.close();
            long elapsed = System.nanoTime() - start;
            assertTrue(elapsed < TimeUnit.SECONDS.toNanos(6), "shutdown exceeded bounded drain");
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
            assertFalse(writer.submit(DAY, 2, "{}"));
            assertTrue(writer.dropped() >= 2);
        } finally {
            writer.close();
        }
    }

    private static Path file(Path directory, String day) {
        return directory.resolve("observations-" + day + ".jsonl");
    }
}
