package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.utils.Messages;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.MessageFormat;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class DeleteCommand implements MainCommand.SubCommand {

    private final Stonks plugin;

    public DeleteCommand(Stonks plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(Player player, String[] args) {

        Messages messages = plugin.getMessages();
        CompaniesService companiesService = plugin.getCompaniesService();
        PlayersService playersService = plugin.getPlayersService();
        Economy vaultEconomy = plugin.getEconomy();

        if (args.length < 1) {
            player.sendMessage(messages.getPluginPrefix() + "Usage: /invest company delete [ID]");
            return true;
        }

        try {
            long companyId = Long.parseLong(args[0]);

            if (!companiesService.companyExists(companyId)) {
                player.sendMessage(messages.getPluginPrefix() + messages.getInvalidCompany());
                return true;
            }

            CompanyDao companyToDelete = companiesService.getCompanyById(companyId);
            if (!playersService.hasSharesInCompany(player.getUniqueId(), companyToDelete.getId(), companyToDelete.getTotalShares())) {
                player.sendMessage(messages.getPluginPrefix() + messages.getCannotDeleteNotOwnedCompany());
                return true;
            }

            double companyValuation = companyToDelete.getTotalShares() * companyToDelete.getCurrentSharePrice();

            playersService.removeSharesFromPlayer(player.getUniqueId(), companyToDelete.getId(), companyToDelete.getTotalShares());
            vaultEconomy.depositPlayer(player, companyValuation);
            companiesService.deleteCompany(companyId);

            player.sendMessage(messages.getPluginPrefix() + MessageFormat.format(messages.getSoldActions(), companyToDelete.getTotalShares(), companyValuation));
            player.sendMessage(messages.getPluginPrefix() + MessageFormat.format(messages.getDeletedCompany(), companyToDelete.getName()));

        } catch (NumberFormatException e) {
            player.sendMessage(messages.getPluginPrefix() + "Invalid Company ID.");
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) return Collections.emptyList();
        Player player = (Player) sender;
        if (args.length == 1) {
            return plugin.getPlayersService().getInvestments(player.getUniqueId()).stream()
                    .filter(inv -> plugin.getCompaniesService().companyExists(inv.getCompanyId()) && plugin.getCompaniesService().getCompanyById(inv.getCompanyId()).getTotalShares() <= inv.getSharesAmount())
                    .map(inv -> String.valueOf(inv.getCompanyId()))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}