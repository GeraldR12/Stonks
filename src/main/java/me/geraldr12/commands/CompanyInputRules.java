package me.geraldr12.commands;

import org.bukkit.Material;

/** Checks and messages shared by /invest company create and /invest admin create. */
final class CompanyInputRules {

    /** Longest name that still fits on the first line of a ticker sign. */
    static final int MAX_NAME_LENGTH = 16;

    static final String BAD_NUMBERS = "Risk, shares and price must be numbers. The company name must be one word"
            + " (use _ instead of spaces), e.g. /invest company create Bank_Corp 1 100 40";

    private CompanyInputRules() {
    }

    /** The icon material, or null when the argument is not an item a menu can show. */
    static Material parseIcon(String argument) {
        if (argument == null || argument.isBlank()) return null;
        String id = argument.trim().toUpperCase(java.util.Locale.ROOT);
        if (id.startsWith("MINECRAFT:")) id = id.substring("MINECRAFT:".length());
        Material material = Material.matchMaterial(id);
        if (material == null || !material.isItem() || material.isAir()) return null;
        return material;
    }

    static String badIconMessage(String argument) {
        return "\"" + argument + "\" is not an item. Use the item's ID, e.g. GOLD_INGOT, DIAMOND or WHEAT"
                + " (press F3+H in game and hover over an item to see its ID).";
    }

    /** Null when the name is fine, otherwise the reason it isn't. */
    static String nameProblem(String name) {
        if (name == null || name.isBlank()) return "The company needs a name.";
        if (name.length() > MAX_NAME_LENGTH) {
            return "Company names can have at most " + MAX_NAME_LENGTH + " characters.";
        }
        return null;
    }

    /** Highest risk level: one per entry in Stonks.Taxes.CompanyCreation, or 0 when unknown. */
    static int maxRisk(org.bukkit.plugin.Plugin plugin) {
        if (plugin == null || plugin.getConfig() == null) return 0;
        return plugin.getConfig().getDoubleList("Stonks.Taxes.CompanyCreation").size();
    }

    /** Null when the risk is valid for the configured tax list, otherwise the reason. */
    static String riskProblem(int risk, int maxRisk) {
        if (maxRisk <= 0) return null;
        if (risk < 1 || risk > maxRisk) return "Risk must be a number from 1 to " + maxRisk + ".";
        return null;
    }
}
