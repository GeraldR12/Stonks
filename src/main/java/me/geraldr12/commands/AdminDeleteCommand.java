package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.utils.Messages;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.MessageFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Admin Delete Company Command
 */
public class AdminDeleteCommand implements MainCommand.SubCommand {

    private final Stonks plugin;

    public AdminDeleteCommand(Stonks plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(Player player, String[] args) {

        Messages messages = plugin.getMessages();
        CompaniesService companiesService = plugin.getCompaniesService();
        PlayersService playersService = plugin.getPlayersService();

        if (!player.hasPermission("blockstreet.admin.command.delete")) {
            player.sendMessage(messages.getPluginPrefix() + messages.getNoPermission());
            return true;
        }
        if (plugin.getTransactionJournal() == null || !plugin.isJournalReady()) {
            player.sendMessage(messages.getPluginPrefix()
                    + "Administrative company deletion is temporarily unavailable.");
            return true;
        }
        if (!administrativeDeletionSupported()) {
            player.sendMessage(messages.getPluginPrefix()
                    + "Administrative company deletion is disabled until durable settlement is available.");
            return true;
        }

        // Validate argument length
        if (args.length < 1) {
            player.sendMessage(messages.getPluginPrefix() + "Usage: /invest admin delete <ID>");
            return true;
        }

        try {
            long companyId = Long.parseLong(args[0]);
            if (companyId <= 0) {
                player.sendMessage(messages.getPluginPrefix() + messages.getInvalidCompany());
                return true;
            }

            if (!companiesService.companyExists(companyId)) {
                player.sendMessage(messages.getPluginPrefix() + messages.getInvalidCompany());
                return true;
            }

            CompanyDao companyToDelete = companiesService.getCompanyById(companyId);
            if (companyToDelete == null || !companiesService.deleteCompany(companyId)) {
                player.sendMessage(messages.getPluginPrefix() + "Unable to delete company.");
                return true;
            }
            playersService.cleanUpInvestmentsForOnlinePlayers(companiesService.getAllCompanies());

            player.sendMessage(messages.getPluginPrefix() + MessageFormat.format(messages.getDeletedCompany(), companyToDelete.getName()));

        } catch (NumberFormatException e) {
            player.sendMessage(messages.getPluginPrefix() + "Error: Company ID must be a number.");
        }

        return true;
    }

    private boolean administrativeDeletionSupported() {
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        // Tab complete company IDs for deletion
        if (args.length == 1) {
            return plugin.getCompaniesService().getAllCompanies().stream()
                    .map(CompanyDao::getId)
                    .map(String::valueOf)
                    .filter(id -> id.startsWith(args[0]))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}