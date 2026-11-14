package me.geraldr12.commands;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompanyInputRulesTest {

    @Test
    void iconAcceptsItemIdsInAnyCase() {
        assertEquals(Material.GOLD_INGOT, CompanyInputRules.parseIcon("gold_ingot"));
        assertEquals(Material.DIAMOND, CompanyInputRules.parseIcon(" DIAMOND "));
        assertEquals(Material.WHEAT, CompanyInputRules.parseIcon("minecraft:wheat"));
    }

    @Test
    void iconRejectsUnknownAirAndNonItems() {
        assertNull(CompanyInputRules.parseIcon("gold"));
        assertNull(CompanyInputRules.parseIcon("AIR"));
        assertNull(CompanyInputRules.parseIcon("WATER"));
        assertNull(CompanyInputRules.parseIcon(""));
        assertNull(CompanyInputRules.parseIcon(null));
        assertTrue(CompanyInputRules.badIconMessage("gold").contains("GOLD_INGOT"));
    }

    @Test
    void namesUpToSixteenCharacters() {
        assertNull(CompanyInputRules.nameProblem("Bank_Corp"));
        assertNull(CompanyInputRules.nameProblem("A".repeat(16)));
        assertNotNull(CompanyInputRules.nameProblem("A".repeat(17)));
        assertNotNull(CompanyInputRules.nameProblem(" "));
    }

    @Test
    void riskFollowsTheConfiguredTaxList() {
        assertNull(CompanyInputRules.riskProblem(1, 5));
        assertNull(CompanyInputRules.riskProblem(5, 5));
        assertEquals("Risk must be a number from 1 to 5.", CompanyInputRules.riskProblem(6, 5));
        assertNotNull(CompanyInputRules.riskProblem(0, 5));
        assertNull(CompanyInputRules.riskProblem(9, 0), "no tax list configured: leave it to the service");
    }
}
