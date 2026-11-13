package me.geraldr12.data.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class PriceHistoryLogTest {
    @TempDir Path temp;

    private static final Instant T = Instant.parse("2026-11-13T21:24:40Z");

    @Test
    void writesHeaderOnceThenOneLinePerChange() throws Exception {
        PriceHistoryLog log = new PriceHistoryLog(temp.resolve("history"), () -> true);
        log.append(T, 1, "DataCorp", 1, PriceHistoryLog.Source.UPDATE, 0, 30.0, 30.4567891234, 0.015226);
        log.append(T, 1, "DataCorp", 1, PriceHistoryLog.Source.BUY, 10, 30.4567891234, 30.466, 0.0003);
        log.append(T, 1, "DataCorp", 1, PriceHistoryLog.Source.CRASH, 0, 30.466, 0.0, -1.0);
        List<String> lines = Files.readAllLines(temp.resolve("history/1.csv"));
        assertEquals(List.of(
                PriceHistoryLog.HEADER,
                "2026-11-13 21:24:40,1,DataCorp,1,update,,30,30.456789,0.015226",
                "2026-11-13 21:24:40,1,DataCorp,1,buy,10,30.456789,30.466,0.0003",
                "2026-11-13 21:24:40,1,DataCorp,1,crash,,30.466,0,-1"), lines);
    }

    @Test
    void eachCompanyGetsItsOwnFile() {
        PriceHistoryLog log = new PriceHistoryLog(temp, () -> true);
        log.append(T, 0, "A", 1, PriceHistoryLog.Source.UPDATE, 0, 1, 2, 1);
        log.append(T, 7, "B", 5, PriceHistoryLog.Source.SELL, 3, 2, 1, -0.5);
        assertTrue(Files.exists(temp.resolve("0.csv")));
        assertTrue(Files.exists(temp.resolve("7.csv")));
    }

    @Test
    void namesWithCommasOrQuotesStayInOneColumn() {
        assertEquals("\"Acme, Inc\"", PriceHistoryLog.quote("Acme, Inc"));
        assertEquals("\"Say \"\"hi\"\"\"", PriceHistoryLog.quote("Say \"hi\""));
        assertEquals("Plain", PriceHistoryLog.quote("Plain"));
        assertEquals("", PriceHistoryLog.quote(null));
    }

    @Test
    void disabledWritesNothingAndCanBeTurnedOnLive() {
        AtomicBoolean enabled = new AtomicBoolean(false);
        PriceHistoryLog log = new PriceHistoryLog(temp.resolve("h"), enabled::get);
        log.append(T, 1, "A", 1, PriceHistoryLog.Source.UPDATE, 0, 1, 2, 1);
        assertFalse(Files.exists(temp.resolve("h")));
        enabled.set(true);
        log.append(T, 1, "A", 1, PriceHistoryLog.Source.UPDATE, 0, 1, 2, 1);
        assertTrue(Files.exists(temp.resolve("h/1.csv")));
    }

    @Test
    void aDiskProblemNeverThrows() throws Exception {
        Path blocker = temp.resolve("history");
        Files.writeString(blocker, "not a directory");
        PriceHistoryLog log = new PriceHistoryLog(blocker, () -> true);
        assertDoesNotThrow(() -> log.append(T, 1, "A", 1, PriceHistoryLog.Source.UPDATE, 0, 1, 2, 1));
        assertDoesNotThrow(() -> PriceHistoryLog.record(null, 1, "A", 1, PriceHistoryLog.Source.UPDATE, 0, 1, 2, 1));
    }

    @Test
    void numbersUseADotWhateverTheLocale() {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("pt-BR"));
            assertEquals("1234.5", PriceHistoryLog.number(1234.5));
            assertEquals("", PriceHistoryLog.number(Double.NaN));
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }
}
