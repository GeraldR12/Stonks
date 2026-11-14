package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.ui.PluginGuiType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class PlayerCompanyCommand implements CommandExecutor {

    private final Stonks plugin;

    public PlayerCompanyCommand(Stonks plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;

        Player player = (Player) sender;
        if (!player.hasPermission("stonks.command.company")) {
            player.sendMessage(plugin.getMessages().getPluginPrefix() + " No permission.");
            return true;
        }

        // Opens the personal portfolio
        plugin.getGuiManager().navigate(player, PluginGuiType.PLAYER_COMPANIES_GUI);
        return true;
    }
}