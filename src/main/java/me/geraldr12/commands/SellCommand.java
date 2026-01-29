package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.dao.InvestmentDao;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.utils.Messages;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.MessageFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Sell Command
 * Converted to standard Bukkit API.
 */
public class SellCommand implements MainCommand.SubCommand {

    private final Stonks plugin;

    public SellCommand(Stonks plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(Player player, String[] args) {

        Messages messages = plugin.getMessages();
        CompaniesService companiesService = plugin.getCompaniesService();
        PlayersService playersService = plugin.getPlayersService();
        Economy economy = plugin.getEconomy();

        // Validate argument length: /invest sell <companyID> <amount>
        if (args.length < 2) {
            player.sendMessage(messages.getPluginPrefix() + "Usage: /invest sell <companyID> <amount>");
            return true;
        }

        try {
            long companyId = Long.parseLong(args[0]);
            int sellingAmount = Integer.parseInt(args[1]);

            if (!companiesService.companyExists(companyId)) {
                player.sendMessage(messages.getPluginPrefix() + messages.getInvalidCompany());
                return true;
            }

            // If the player doesn't have at least "sellingAmount" shares in the company, then we don't allow him to sell.
            if (!playersService.hasSharesInCompany(player.getUniqueId(), companyId, sellingAmount)) {
                player.sendMessage(messages.getPluginPrefix() + messages.getPlayerNoActions());
                return true;
            }

            double sharesValue = companiesService.getCompanyInvestmentValue(companyId, sellingAmount);

            // We remove the shares from the player and give him the money.
            economy.depositPlayer(player, sharesValue);
            playersService.removeSharesFromPlayer(player.getUniqueId(), companyId, sellingAmount);
            companiesService.addSharesToCompany(companyId, sellingAmount);

            player.sendMessage(messages.getPluginPrefix() + MessageFormat.format(messages.getSoldActions(),
                    String.valueOf(sellingAmount), String.format("%.2f", sharesValue)));

        } catch (NumberFormatException e) {
            player.sendMessage(messages.getPluginPrefix() + "Error: Company ID and Amount must be numbers.");
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) return List.of();
        Player player = (Player) sender;

        if (args.length == 1) {
            CompaniesService companiesService = plugin.getCompaniesService();
            return companiesService.getAllCompanies().stream()
                    .map(CompanyDao::getId)
                    .map(String::valueOf)
                    .filter(id -> id.startsWith(args[0]))
                    .collect(Collectors.toList());
        } else if (args.length == 2) {
            PlayersService playersService = plugin.getPlayersService();
            try {
                long inputId = Long.parseLong(args[0]);
                return playersService.getInvestments(player.getUniqueId()).stream()
                        .filter(investment -> investment.getCompanyId() == inputId)
                        .map(InvestmentDao::getSharesAmount)
                        .map(String::valueOf)
                        .filter(amount -> amount.startsWith(args[1]))
                        .collect(Collectors.toList());
            } catch (NumberFormatException e) {
                return List.of();
            }
        }
        return List.of();
    }
}