package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.utils.Messages;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.MessageFormat;
import java.util.List;
import java.util.stream.Collectors;

public class SellCommand implements MainCommand.SubCommand {

    private final Stonks plugin;

    public SellCommand(Stonks plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(Player player, String[] args) {
        Messages messages = plugin.getMessages();
        Economy vaultEconomy = plugin.getEconomy();
        CompaniesService companiesService = plugin.getCompaniesService();
        PlayersService playersService = plugin.getPlayersService();

        if (args.length < 2) {
            player.sendMessage(messages.getPluginPrefix() + "Usage: /invest sell <id> <amount>");
            return true;
        }

        try {
            long companyId = Long.parseLong(args[0]);
            int numberOfSharesToSell = Integer.parseInt(args[1]);

            if (!playersService.hasSharesInCompany(player.getUniqueId(), companyId, numberOfSharesToSell)) {
                player.sendMessage(messages.getPluginPrefix() + messages.getInsufficientActions());
                return true;
            }

            CompanyDao company = companiesService.getCompanyById(companyId);
            double salePrice = companiesService.getCompanyInvestmentValue(companyId, numberOfSharesToSell);

            // --- TRANSACTION LOGIC (Synchronous) ---
            playersService.removeSharesFromPlayer(player.getUniqueId(), companyId, numberOfSharesToSell);
            companiesService.addSharesToCompany(companyId, numberOfSharesToSell);
            vaultEconomy.depositPlayer(player, salePrice);

            // --- MARKET IMPACT (Asynchronous to prevent Event Thread Error) ---
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                companiesService.applyMarketImpact(player.getUniqueId(), companyId, numberOfSharesToSell, false);
            });

            player.sendMessage(messages.getPluginPrefix() + MessageFormat.format(messages.getSoldActions(), numberOfSharesToSell));

        } catch (NumberFormatException e) {
            player.sendMessage(messages.getPluginPrefix() + "Error: ID and Amount must be numbers.");
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return plugin.getCompaniesService().getAllCompanies().stream()
                    .map(company -> String.valueOf(company.getId()))
                    .collect(Collectors.toList());
        } else if (args.length == 2) {
            return List.of("1", "10", "100");
        }
        return List.of();
    }
}