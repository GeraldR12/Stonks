package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.utils.Messages;
import me.geraldr12.ui.GuiManager;
import me.geraldr12.ui.PluginGuiType;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Company Command
 * Converted to standard Bukkit API via SubCommand interface.
 */
public class CompanyCommand implements MainCommand.SubCommand {

    private final Stonks plugin;

    public CompanyCommand(Stonks plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(Player player, String[] args) {

        Messages messages = plugin.getMessages();
        GuiManager guiManager = plugin.getGuiManager();

        // Check permission manually
        if (!player.hasPermission("blockstreet.command.company")) {
            player.sendMessage(messages.getPluginPrefix() + messages.getNoPermission());
            return true;
        }

        // Logic for /invest company info <ID> (if passed from CompanySubCommand)
        if (args.length > 0) {
            try {
                long companyId = Long.parseLong(args[0]);
                // If you want to show the GUI even with an ID:
                player.sendMessage(messages.getPluginPrefix() + " Opening company menu for ID: " + companyId);
                guiManager.navigate(player, PluginGuiType.PLAYER_COMPANIES_GUI);
                return true;
            } catch (NumberFormatException ignored) {}
        }

        // Default behavior: Open the GUI
        player.sendMessage(messages.getPluginPrefix() + " Opening your company menu...");
        guiManager.navigate(player, PluginGuiType.PLAYER_COMPANIES_GUI);

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
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