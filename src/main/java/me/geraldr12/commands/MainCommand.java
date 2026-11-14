package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.ui.PluginGuiType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public class MainCommand implements CommandExecutor, TabCompleter {

    private final Stonks plugin;
    private final Map<String, SubCommand> subCommands = new HashMap<>();
    private final DiagnosticsCommand diagnostics;

    public MainCommand(Stonks plugin) {
        this.plugin = plugin;
        this.diagnostics = new DiagnosticsCommand(plugin);
        subCommands.put("buy", new BuyCommand(plugin));
        subCommands.put("sell", new SellCommand(plugin));
        subCommands.put("portfolio", new PortfolioCommand(plugin));
        subCommands.put("companies", new CompaniesCommand(plugin));
        subCommands.put("company", new CompanySubCommand(plugin));
        subCommands.put("admin", new AdminSubCommand(plugin));
        subCommands.put("reconcile", new ReconcileCommand(plugin));
        subCommands.put("reload", new ReloadCommand(plugin));
        subCommands.put("info", new InfoCommand(plugin));
        subCommands.put("help", new HelpCommand(plugin));
        subCommands.put("notification", new ToggleNotificationCommand(plugin));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("stonks.use")
                && !(args.length > 0 && args[0].equalsIgnoreCase("reconcile"))) {
            sender.sendMessage("You do not have permission to use Stonks commands.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reconcile")) {
            return ((ReconcileCommand) subCommands.get("reconcile")).execute(sender,
                    Arrays.copyOfRange(args, 1, args.length));
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("diagnostics")) {
            if (!sender.hasPermission("stonks.admin.reconcile")) {
                sender.sendMessage("You do not have permission to use Stonks diagnostics.");
                return true;
            }
            return diagnostics.execute(sender, Arrays.copyOfRange(args, 1, args.length));
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        Player player = (Player) sender;

        // Base command /invest with no args opens global GUI
        if (args.length == 0) {
            plugin.getGuiManager().navigate(player, PluginGuiType.MAIN_MENU_GUI);
            return true;
        }

        // Route to Sub-command
        SubCommand sub = subCommands.get(args[0].toLowerCase());
        if (sub != null) {
            // Pass the player and the remaining arguments (omitting the sub-command name)
            return sub.onCommand(player, Arrays.copyOfRange(args, 1, args.length));
        }

        player.sendMessage(plugin.getMessages().getPluginPrefix() + " Unknown sub-command.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return subCommands.keySet().stream()
                    .filter(k -> k.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        SubCommand sub = subCommands.get(args[0].toLowerCase());
        if (sub != null) {
            return sub.onTabComplete(sender, Arrays.copyOfRange(args, 1, args.length));
        }

        return Collections.emptyList();
    }

    // This interface ensures all sub-commands have the same structure
    public interface SubCommand {
        boolean onCommand(Player player, String[] args);
        List<String> onTabComplete(CommandSender sender, String[] args);
    }
}