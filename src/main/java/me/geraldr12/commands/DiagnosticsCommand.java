package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.integration.AdvancedJobsAdapter;
import me.geraldr12.integration.CommerceSnapshot;
import me.geraldr12.integration.QuickShopAdapter;
import me.geraldr12.integration.StonksIntegrationAdapter;
import me.geraldr12.integration.TownFundamentalsSnapshotService;
import me.geraldr12.integration.TownyAdapter;
import me.geraldr12.integration.VaultAdapter;
import me.geraldr12.integration.TownSnapshot;
import org.bukkit.command.CommandSender;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class DiagnosticsCommand {
    private final List<StonksIntegrationAdapter> adapters;
    private final TownFundamentalsSnapshotService snapshots;

    public DiagnosticsCommand(Stonks plugin) {
        TownyAdapter towny = new TownyAdapter();
        QuickShopAdapter quickShop = new QuickShopAdapter();
        snapshots = plugin.getTownFundamentalsSnapshotService();
        adapters = List.of(towny, new VaultAdapter(), quickShop, new AdvancedJobsAdapter());
    }

    static List<String> formatTownCommerce(CommerceSnapshot commerce) {
        String transactionCount = commerce.transactionCount().available()
                ? Long.toString(commerce.transactionCount().value()) : "UNAVAILABLE";
        String quantity = commerce.quantity().available()
                ? Long.toString(commerce.quantity().value()) : "UNAVAILABLE";
        String monetaryVolume = commerce.monetaryVolume().available()
                ? Double.toString(commerce.monetaryVolume().value()) : "UNAVAILABLE";
        return List.of(
                "commerce.status=" + commerce.status(),
                "commerce.transactionCount=" + transactionCount,
                "commerce.quantity=" + quantity,
                "commerce.monetaryVolume=" + monetaryVolume,
                "commerce.observedAt=" + commerce.observedAt(),
                "commerce.window=" + commerce.window());
    }

    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /invest diagnostics adapters|town <town>");
            return true;
        }
        if (args[0].equalsIgnoreCase("adapters")) {
            for (StonksIntegrationAdapter adapter : adapters) {
                sender.sendMessage(adapter.id() + " version=" + adapter.version()
                        + " status=" + adapter.status()
                        + " capabilities=" + adapter.capabilities());
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("town") && args.length >= 2) {
            TownSnapshot town = snapshots.captureOnMainThread(args[1], Instant.now());
            if (town == null) {
                sender.sendMessage("Town unavailable: " + args[1]);
                return true;
            }
            sender.sendMessage("Town id=" + town.townId());
            sender.sendMessage("Town name=" + town.name());
            sender.sendMessage("Town nation=" + town.nation().orElse("UNAVAILABLE"));
            sender.sendMessage("Town population=" + town.residentCount());
            sender.sendMessage("Town claimedLand=" + town.claimedLand());
            sender.sendMessage("Town treasury=" + (town.treasury().available()
                    ? town.treasury().value() : "UNAVAILABLE"));
            CommerceSnapshot commerce = snapshots.commerce(args[1], Instant.now(), Duration.ofMinutes(5));
            for (String line : formatTownCommerce(commerce)) sender.sendMessage(line);
            sender.sendMessage("Town production=" + snapshots.production(Instant.now(), Duration.ofMinutes(5)).status());
            sender.sendMessage("Town observedAt=" + town.observedAt());
            return true;
        }
        sender.sendMessage("Usage: /invest diagnostics adapters|town <town>");
        return true;
    }
}
