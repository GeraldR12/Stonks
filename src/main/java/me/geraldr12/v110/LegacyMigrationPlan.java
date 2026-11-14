package me.geraldr12.v110;

import me.geraldr12.data.dao.LocationDao;
import me.geraldr12.v110.MigrationState.MigrationException;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

record LegacyMigrationPlan(List<Company> companies, List<Player> players, List<Sign> signs) {

    record Company(int id, String name, int risk, int shares, double price) {
    }

    record Position(int companyId, int amount) {
    }

    record Player(String name, UUID id, List<Position> positions) {
    }

    record Sign(int id, int companyId, LocationDao location) {
    }

    boolean hasCompany(int id) {
        return companies.stream().anyMatch(company -> company.id() == id);
    }

    int maxCompanyId() {
        return companies.stream().mapToInt(Company::id).max().orElse(-1);
    }

    static LegacyMigrationPlan parse(File directory, Function<File, ConfigurationSection> loader,
                                     Function<String, OfflinePlayer> offlinePlayers,
                                     BiFunction<ConfigurationSection, String, Location> locations) {
        List<Company> companies = parseCompanies(new File(directory, "companies.yml"), loader);
        Set<Integer> companyIds = new HashSet<>();
        companies.forEach(company -> companyIds.add(company.id()));
        List<Player> players = parsePlayers(new File(directory, "players.yml"), loader, companyIds, offlinePlayers);
        List<Sign> signs = parseSigns(new File(directory, "signs.yml"), loader, companyIds, locations);
        return new LegacyMigrationPlan(List.copyOf(companies), List.copyOf(players), List.copyOf(signs));
    }

    private static List<Company> parseCompanies(File file, Function<File, ConfigurationSection> loader) {
        List<Company> result = new ArrayList<>();
        ConfigurationSection config = load(file, loader);
        ConfigurationSection section = config == null ? null : section(config, "Companies");
        if (section == null) {
            return result;
        }
        Set<Integer> seen = new HashSet<>();
        for (String key : section.getKeys(false)) {
            int id = parseId(key, "company");
            if (id == Integer.MAX_VALUE || !seen.add(id)) {
                throw invalid("Invalid or duplicated company id: " + key);
            }
            String path = "Companies." + key;
            Object name = config.get(path + ".Name");
            if (!(name instanceof String text) || text.isBlank()) {
                throw invalid(path + ".Name is missing or blank");
            }
            double price = readDouble(config, path + ".Price");
            int risk = readInt(config, path + ".Risk");
            if (risk < 0) {
                throw invalid(path + ".Risk is negative");
            }
            int shares = readInt(config, path + ".AvailableStocks");
            if (shares < -1) {
                throw invalid(path + ".AvailableStocks is invalid");
            }
            result.add(new Company(id, text, risk, shares, price));
        }
        return result;
    }

    private static List<Player> parsePlayers(File file, Function<File, ConfigurationSection> loader, Set<Integer> companyIds,
                                             Function<String, OfflinePlayer> offlinePlayers) {
        List<Player> result = new ArrayList<>();
        ConfigurationSection config = load(file, loader);
        ConfigurationSection section = config == null ? null : section(config, "Players");
        if (section == null) {
            return result;
        }
        Set<UUID> seen = new HashSet<>();
        for (String name : section.getKeys(false)) {
            ConfigurationSection companies = section(config, "Players." + name + ".Companies");
            if (companies == null) {
                continue;
            }
            List<Position> positions = new ArrayList<>();
            for (String key : companies.getKeys(false)) {
                int companyId = parseId(key, "company reference");
                if (!companyIds.contains(companyId)) {
                    throw invalid("Player " + name + " references unknown company " + companyId);
                }
                int amount = readInt(config, "Players." + name + ".Companies." + key + ".Amount");
                if (amount < 0) {
                    throw invalid("Player " + name + " has a negative position in company " + companyId);
                }
                if (amount > 0) {
                    positions.add(new Position(companyId, amount));
                }
            }
            if (positions.isEmpty()) {
                continue;
            }
            OfflinePlayer player = offlinePlayers.apply(name);
            UUID id = player == null ? null : player.getUniqueId();
            if (id == null) {
                throw invalid("Player " + name + " could not be resolved");
            }
            if (!seen.add(id)) {
                throw invalid("Player " + name + " resolves to a duplicated identity");
            }
            result.add(new Player(name, id, List.copyOf(positions)));
        }
        return result;
    }

    private static List<Sign> parseSigns(File file, Function<File, ConfigurationSection> loader, Set<Integer> companyIds,
                                         BiFunction<ConfigurationSection, String, Location> locations) {
        List<Sign> result = new ArrayList<>();
        ConfigurationSection config = load(file, loader);
        ConfigurationSection section = config == null ? null : section(config, "Signs");
        if (section == null) {
            return result;
        }
        Set<Integer> seen = new HashSet<>();
        for (String key : section.getKeys(false)) {
            int id = parseId(key, "sign");
            if (!seen.add(id)) {
                throw invalid("Duplicated sign id: " + key);
            }
            int companyId = readInt(config, "Signs." + key + ".CompanyId");
            if (!companyIds.contains(companyId)) {
                throw invalid("Sign " + id + " references unknown company " + companyId);
            }
            Location location = locations.apply(config, "Signs." + key + ".Location");
            if (location == null) {
                throw invalid("Sign " + id + " has no valid location");
            }
            LocationDao locationDao;
            try {
                locationDao = LocationDao.fromBukkitLocation(location);
            } catch (IllegalArgumentException e) {
                throw new MigrationException("Sign " + id + " has no valid world", e);
            }
            result.add(new Sign(id, companyId, locationDao));
        }
        return result;
    }

    private static ConfigurationSection load(File file, Function<File, ConfigurationSection> loader) {
        return file.exists() ? loader.apply(file) : null;
    }

    static ConfigurationSection loadYaml(File file) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            throw new MigrationException("Legacy file is unreadable: " + file.getName(), e);
        }
        return config;
    }

    private static ConfigurationSection section(ConfigurationSection config, String path) {
        if (!config.isSet(path)) {
            return null;
        }
        if (!config.isConfigurationSection(path)) {
            throw invalid(path + " is malformed");
        }
        return config.getConfigurationSection(path);
    }

    private static int parseId(String key, String label) {
        if (!key.matches("\\d+")) {
            throw invalid("Invalid " + label + " id: " + key);
        }
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException e) {
            throw new MigrationException("Invalid " + label + " id: " + key, e);
        }
    }

    private static int readInt(ConfigurationSection config, String path) {
        Object value = config.get(path);
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw invalid(path + " is missing or not an integer");
        }
        long number = ((Number) value).longValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw invalid(path + " is out of range");
        }
        return (int) number;
    }

    private static double readDouble(ConfigurationSection config, String path) {
        Object value = config.get(path);
        if (!(value instanceof Number number)) {
            throw invalid(path + " is missing or not numeric");
        }
        double result = number.doubleValue();
        if (!Double.isFinite(result) || result < 0) {
            throw invalid(path + " is not a finite non-negative number");
        }
        return result;
    }

    private static MigrationException invalid(String message) {
        return new MigrationException(message, null);
    }
}
