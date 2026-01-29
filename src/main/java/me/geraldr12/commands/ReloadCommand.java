package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.utils.Messages;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Reload Command
 * Converted to standard Bukkit API via SubCommand interface.
 */
public class ReloadCommand implements MainCommand.SubCommand {

	private final Stonks plugin;

	public ReloadCommand(Stonks plugin) {
		this.plugin = plugin;
	}

	@Override
	public boolean onCommand(Player player, String[] args) {

		Messages messages = plugin.getMessages();

		// Check permission manually
		if (!player.hasPermission("blockstreet.admin.reload")) {
			player.sendMessage(messages.getPluginPrefix() + messages.getNoPermission());
			return true;
		}

		// Standard reload logic
		plugin.reloadConfig();
		messages.reload();

		plugin.stopSchedulers();
		plugin.registerSchedulers();

		player.sendMessage(messages.getPluginPrefix() + ChatColor.GREEN + messages.getPluginReload());
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, String[] args) {
		return List.of();
	}
}