package me.geraldr12.data.services;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Appends every share price change to {@code history/<companyId>.csv}, so the full price history
 * survives the 500-entry limit of the in-game history and the company itself being deleted.
 *
 * <p>One line per change: the timed market update, a crash, or the price move caused by a trade.
 * No player names are written. A write that fails is logged once and never stops the trade or update.</p>
 */
public class PriceHistoryLog {

    public static final String HEADER = "time_utc,company_id,company_name,risk,source,shares,old_price,new_price,variation";

    public enum Source { UPDATE, CRASH, BUY, SELL }

    private static final Logger LOG = Logger.getLogger("Stonks");
    /** Plain UTC time that Excel and Google Sheets read as a date. */
    private static final java.time.format.DateTimeFormatter TIME =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneOffset.UTC);

    private final Path directory;
    private final BooleanSupplier enabled;
    private boolean failureLogged;

    public PriceHistoryLog(Path directory, BooleanSupplier enabled) {
        this.directory = directory;
        this.enabled = enabled;
    }

    /** Null-safe entry point; tests and partial startups often run without a log. */
    public static void record(PriceHistoryLog log, long companyId, String companyName, int risk, Source source,
                              int shares, double oldPrice, double newPrice, double variation) {
        if (log == null) return;
        log.append(Instant.now(), companyId, companyName, risk, source, shares, oldPrice, newPrice, variation);
    }

    public synchronized void append(Instant time, long companyId, String companyName, int risk, Source source,
                                    int shares, double oldPrice, double newPrice, double variation) {
        try {
            if (!enabled.getAsBoolean()) return;
            Files.createDirectories(directory);
            Path file = directory.resolve(companyId + ".csv");
            StringBuilder line = new StringBuilder();
            if (!Files.exists(file) || Files.size(file) == 0) {
                line.append(HEADER).append('\n');
            }
            line.append(TIME.format(time)).append(',')
                    .append(companyId).append(',')
                    .append(quote(companyName)).append(',')
                    .append(risk).append(',')
                    .append(source.name().toLowerCase(Locale.ROOT)).append(',')
                    .append(source == Source.BUY || source == Source.SELL ? Integer.toString(shares) : "").append(',')
                    .append(number(oldPrice)).append(',')
                    .append(number(newPrice)).append(',')
                    .append(number(variation)).append('\n');
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            if (!failureLogged) {
                failureLogged = true;
                LOG.log(Level.WARNING, "Unable to write the price history in " + directory + "; prices keep updating.", e);
            }
        }
    }

    static String quote(String value) {
        if (value == null) return "";
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"").replace('\n', ' ').replace('\r', ' ') + '"';
    }

    static String number(double value) {
        if (!Double.isFinite(value)) return "";
        // Up to 6 decimals, no thousands separator, dot as decimal mark whatever the server locale.
        return new java.math.BigDecimal(value).setScale(6, java.math.RoundingMode.HALF_EVEN)
                .stripTrailingZeros().toPlainString();
    }
}
