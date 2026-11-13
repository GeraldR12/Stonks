package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.utils.Messages;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Reload Command
 */
public class ReloadCommand implements MainCommand.SubCommand {

	private final Stonks plugin;

	public ReloadCommand(Stonks plugin) {
		this.plugin = plugin;
	}

	@Override
	public boolean onCommand(Player player, String[] args) {

		Messages messages = plugin.getMessages();

		if (!player.hasPermission("blockstreet.admin.reload")) {
			player.sendMessage(messages.getPluginPrefix() + messages.getNoPermission());
			return true;
		}

		// Standard reload logic
		plugin.reloadConfig();
		messages.reload();

		try {
			plugin.registerSchedulers();
		} catch (IllegalArgumentException e) {
			player.sendMessage(messages.getPluginPrefix() + ChatColor.RED
					+ "Invalid interest interval; scheduler was not started.");
			return true;
		}

		player.sendMessage(messages.getPluginPrefix() + ChatColor.GREEN + messages.getPluginReload());
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, String[] args) {
		return List.of();
	}
}