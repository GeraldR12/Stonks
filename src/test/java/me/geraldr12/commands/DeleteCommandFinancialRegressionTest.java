package me.geraldr12.commands;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.stream.Stream;

class DeleteCommandFinancialRegressionTest {
    @TempDir Path temp;

    @TestFactory
    Stream<DynamicTest> boundaries() {
        return FinancialCommandFixture.boundaries(false).map(boundary -> DynamicTest.dynamicTest(boundary,
                () -> new FinancialCommandFixture(temp.resolve(boundary), false).checkBoundary(boundary)));
    }

    @TestFactory
    Stream<DynamicTest> restart() {
        return FinancialCommandFixture.restarts(temp, false);
    }
}
