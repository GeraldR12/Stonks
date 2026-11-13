package me.geraldr12.shadow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class ObservationWriter implements AutoCloseable {
    private static final int QUEUE_CAPACITY = 10_000;
    private static final long DAILY_LIMIT = 32L * 1024 * 1024;

    interface Sink {
        long size(Path path) throws IOException;
        void append(Path path, byte[] bytes) throws IOException;
    }

    private static final Sink FILES = new Sink() {
        @Override
        public long size(Path path) throws IOException {
            try {
                return Files.size(path);
            } catch (NoSuchFileException exception) {
                return 0;
            }
        }

        @Override
        public void append(Path path, byte[] bytes) throws IOException {
            Files.createDirectories(path.getParent());
            Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    };

    private final Path directory;
    private final Consumer<String> warning;
    private final long limit;
    private final Sink sink;
    private final ThreadPoolExecutor executor;
    private final AtomicLong dropped = new AtomicLong();
    private final Map<LocalDate, Long> sizes = new HashMap<>();
    private final Set<LocalDate> cappedDays = new HashSet<>();
    private final Set<Long> warnedCycles = new HashSet<>();
    private final Object lifecycle = new Object();
    private boolean closed;

    public ObservationWriter(Path directory, Consumer<String> warning) {
        this(directory, warning, QUEUE_CAPACITY, DAILY_LIMIT, FILES);
    }

    ObservationWriter(Path directory, Consumer<String> warning, int capacity, long limit, Sink sink) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.warning = Objects.requireNonNull(warning, "warning");
        this.sink = Objects.requireNonNull(sink, "sink");
        if (capacity < 1 || capacity > QUEUE_CAPACITY || limit < 1) {
            throw new IllegalArgumentException("Invalid observation writer limits");
        }
        this.limit = limit;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), task -> {
                    Thread thread = new Thread(task, "stonks-shadow-observation-writer");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** Returns whether the observation was queued, not whether it was written successfully. */
    public boolean submit(Instant observedAt, long cycle, String json) {
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(json, "json");
        synchronized (lifecycle) {
            if (closed) {
                dropped.incrementAndGet();
                return false;
            }
            try {
                executor.execute(() -> write(observedAt, cycle, json));
                return true;
            } catch (RejectedExecutionException exception) {
                dropped.incrementAndGet();
                return false;
            }
        }
    }

    public long dropped() {
        return dropped.get();
    }

    private void write(Instant observedAt, long cycle, String json) {
        LocalDate day = null;
        try {
            day = observedAt.atOffset(ZoneOffset.UTC).toLocalDate();
            if (cappedDays.contains(day)) {
                dropped.incrementAndGet();
                return;
            }
            Path path = directory.resolve("observations-" + day + ".jsonl");
            Long knownSize = sizes.get(day);
            long size = knownSize == null ? sink.size(path) : knownSize;
            byte[] bytes = (json + "\n").getBytes(StandardCharsets.UTF_8);
            if (size >= limit || bytes.length > limit - size) {
                dropped.incrementAndGet();
                cap(day);
                return;
            }
            sink.append(path, bytes);
            sizes.put(day, size + bytes.length);
            if (size + bytes.length == limit) cap(day);
        } catch (IOException | RuntimeException exception) {
            if (day != null) sizes.remove(day);
            dropped.incrementAndGet();
            if (warnedCycles.add(cycle)) {
                warn("Shadow observation I/O failure in cycle " + cycle + ": "
                        + exception.getClass().getSimpleName());
            }
        }
    }

    private void cap(LocalDate day) {
        if (cappedDays.add(day)) {
            warn("Shadow observation daily byte limit reached for " + day);
        }
    }

    private void warn(String message) {
        try {
            warning.accept(message);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void close() {
        synchronized (lifecycle) {
            if (closed) return;
            closed = true;
            executor.shutdown();
        }
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                dropped.addAndGet(executor.shutdownNow().size());
            }
        } catch (InterruptedException exception) {
            dropped.addAndGet(executor.shutdownNow().size());
            Thread.currentThread().interrupt();
        }
    }
}
