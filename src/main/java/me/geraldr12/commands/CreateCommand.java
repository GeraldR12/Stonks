package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.utils.Messages;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.MessageFormat;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class CreateCommand implements MainCommand.SubCommand {

    private final Stonks plugin;

    public CreateCommand(Stonks plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(Player player, String[] args) {

        Messages messages = plugin.getMessages();
        CompaniesService companiesService = plugin.getCompaniesService();
        PlayersService playersService = plugin.getPlayersService();
        Economy vaultEconomy = plugin.getEconomy();

        if (args.length < 4) {
            player.sendMessage(messages.getPluginPrefix() + "Usage: /invest company create [name] [risk] [shares] [price] [icon]");
            return true;
        }

        try {
            String companyName = args[0];
            int companyRisk = Integer.parseInt(args[1]);
            int companySharesAmount = Integer.parseInt(args[2]);
            double companySharePrice = Double.parseDouble(args[3]);
            Material companyIcon = args.length >= 5 ? Material.matchMaterial(args[4].toUpperCase()) : null;

            double companyCreationTax = companiesService.getCompanyCreationTax(companySharesAmount, companySharePrice, companyRisk);
            if (!vaultEconomy.has(player, companyCreationTax)) {
                player.sendMessage(messages.getPluginPrefix() + MessageFormat.format(messages.getInsufficientMoney(), companyCreationTax));
                return true;
            }

            vaultEconomy.withdrawPlayer(player, companyCreationTax);

            CompanyDao createdCompany = companiesService.createPlayerCompany(companyName, companyRisk, companySharesAmount, companySharePrice, companyIcon, player.getUniqueId().toString());
            companiesService.removeSharesFromCompany(createdCompany.getId(), companySharesAmount);
            playersService.addSharesToPlayer(player.getUniqueId(), createdCompany, companySharesAmount);

            player.sendMessage(messages.getPluginPrefix() + MessageFormat.format(messages.getCreatedCompany(), companyName));

        } catch (NumberFormatException e) {
            player.sendMessage(messages.getPluginPrefix() + "Invalid numbers provided for risk, shares, or price.");
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return IntStream.range(1, 6).mapToObj(String::valueOf).collect(Collectors.toList());
        } else if (args.length == 5) {
            return Arrays.stream(Material.values())
                    .map(Enum::name)
                    .filter(name -> name.toLowerCase().startsWith(args[4].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}