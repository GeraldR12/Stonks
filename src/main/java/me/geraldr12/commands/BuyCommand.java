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
import java.util.List;
import java.util.stream.Collectors;

// Change 'extends BukkitDevCommand' to 'implements MainCommand.SubCommand'
public class BuyCommand implements MainCommand.SubCommand {

    private final Stonks plugin;

    // Use a standard constructor instead of the framework one
    public BuyCommand(Stonks plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(Player player, String[] args) {

        Messages messages = plugin.getMessages();
        Economy vaultEconomy = plugin.getEconomy();
        CompaniesService companiesService = plugin.getCompaniesService();
        PlayersService playersService = plugin.getPlayersService();

        // Validate basic argument length: [id] [amount]
        if (args.length < 2) {
            player.sendMessage(messages.getPluginPrefix() + "Usage: /invest buy <id> <amount>");
            return true;
        }

        try {
            long companyId = Long.parseLong(args[0]);
            int numberOfSharesToBuy = Integer.parseInt(args[1]);

            if (!companiesService.companyExists(companyId)) {
                player.sendMessage(messages.getPluginPrefix() + messages.getInvalidCompany());
                return true;
            }

            CompanyDao company = companiesService.getCompanyById(companyId);
            if (company.isBankrupt()) {
                player.sendMessage(messages.getPluginPrefix() + messages.getCannotBuyBankruptCompany());
                return true;
            }

            if (!companiesService.hasEnoughShares(companyId, numberOfSharesToBuy)) {
                player.sendMessage(messages.getPluginPrefix() + messages.getInsufficientActions());
                return true;
            }

            // Logic check for share limits
            int sharesLimit = plugin.getConfig().getInt("Stonks.Limits.MaxSharesPerPlayer");
            long playerSharesCount = playersService.getTotalPlayerSharesCount(player.getUniqueId());
            if (sharesLimit > 0 && (playerSharesCount + numberOfSharesToBuy) > sharesLimit) {
                player.sendMessage(messages.getPluginPrefix() + MessageFormat.format(messages.getCannotOwnMoreThanMaxShares(), sharesLimit));
                return true;
            }

            double investmentPrice = companiesService.getCompanyInvestmentValue(companyId, numberOfSharesToBuy);
            if (vaultEconomy.getBalance(player) < investmentPrice) {
                player.sendMessage(messages.getPluginPrefix() + MessageFormat.format(messages.getInsufficientMoney(), investmentPrice));
                return true;
            }

            // Transaction
            vaultEconomy.withdrawPlayer(player, investmentPrice);
            companiesService.removeSharesFromCompany(companyId, numberOfSharesToBuy);
            playersService.addSharesToPlayer(player.getUniqueId(), company, numberOfSharesToBuy);

            player.sendMessage(messages.getPluginPrefix() + MessageFormat.format(messages.getBoughtActions(), numberOfSharesToBuy));

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