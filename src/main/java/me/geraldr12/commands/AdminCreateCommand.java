package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.utils.Messages;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class AdminCreateCommand implements MainCommand.SubCommand {

    private final Stonks plugin;

    public AdminCreateCommand(Stonks plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(Player player, String[] args) {
        Messages messages = plugin.getMessages();
        CompaniesService companiesService = plugin.getCompaniesService();

        if (!player.hasPermission("blockstreet.admin.command.create")) {
            player.sendMessage(messages.getPluginPrefix() + messages.getNoPermission());
            return true;
        }
        if (!administrativeCreationSupported()) {
            player.sendMessage(messages.getPluginPrefix()
                    + "Administrative company creation is disabled until durable settlement is available.");
            return true;
        }

        if (args.length < 4) {
            player.sendMessage(messages.getPluginPrefix() + "Usage: /invest admin create <name> <risk> <shares> <price> [icon]");
            return true;
        }

        try {
            String companyName = args[0];
            int companyRisk = Integer.parseInt(args[1]);
            int companySharesAmount = Integer.parseInt(args[2]);
            double companySharePrice = Double.parseDouble(args[3]);

            Material companyIcon = null;
            if (args.length >= 5) {
                companyIcon = Material.matchMaterial(args[4].toUpperCase());
                if (companyIcon == null) {
                    player.sendMessage(messages.getPluginPrefix() + "Invalid company icon.");
                    return true;
                }
            }
            if (companyName.isBlank() || companyRisk <= 0 || companySharesAmount <= 0
                    || !Double.isFinite(companySharePrice) || companySharePrice <= 0) {
                player.sendMessage(messages.getPluginPrefix() + "Invalid company values.");
                return true;
            }

            if (companiesService.createAdminCompany(companyName, companyRisk, companySharesAmount,
                    companySharePrice, companyIcon) == null) {
                player.sendMessage(messages.getPluginPrefix() + "Unable to create company.");
                return true;
            }
            player.sendMessage(messages.getPluginPrefix() + MessageFormat.format(messages.getCreatedCompany(), companyName));

        } catch (NumberFormatException e) {
            player.sendMessage(messages.getPluginPrefix() + "Error: Risk, shares, and price must be numbers.");
        }

        return true;
    }

    private boolean administrativeCreationSupported() {
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        // Tab completion uses 'sender' because that's how it's defined in the interface
        if (args.length == 2) {
            return IntStream.range(1, 6).mapToObj(String::valueOf)
                    .filter(s -> s.startsWith(args[1]))
                    .collect(Collectors.toList());
        }

        if (args.length == 5) {
            return Arrays.stream(Material.values())
                    .map(Enum::name)
                    .filter(name -> name.toLowerCase().startsWith(args[4].toLowerCase()))
                    .collect(Collectors.toList());
        }

        return List.of();
    }
}