package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.utils.Messages;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Help Command
 * Standard Bukkit conversion.
 */
public class HelpCommand implements MainCommand.SubCommand {

    private final Stonks plugin;

    public HelpCommand(Stonks plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(Player player, String[] args) {

        Messages messages = plugin.getMessages();

        player.sendMessage(messages.getPluginHeader());
        player.sendMessage("");

        // Manually define the help entries since the framework registry is gone
        sendHelpLine(player, "buy", "<id> <amount>", "buyCommand.description");
        sendHelpLine(player, "sell", "<id> <amount>", "sellCommand.description");
        sendHelpLine(player, "portfolio", "", "portfolioCommand.description");
        sendHelpLine(player, "companies", "", "companiesCommand.description");
        sendHelpLine(player, "company info", "<id>", "companyCommand.description");

        if (player.hasPermission("blockstreet.admin.command.create")) {
            sendHelpLine(player, "admin create", "<name> <risk> <shares> <price> [icon]", "adminCreateCommand.description");
        }

        if (player.hasPermission("blockstreet.admin.command.delete")) {
            sendHelpLine(player, "admin delete", "<id>", "adminDeleteCommand.description");
        }

        player.sendMessage("");
        player.sendMessage(messages.getPluginFooter());

        return true;
    }

    private void sendHelpLine(Player player, String cmdAlias, String args, String descKey) {
        Messages messages = plugin.getMessages();
        String fullCmd = "/invest " + cmdAlias + " " + args;

        TextComponent line = new TextComponent("  • /invest " + cmdAlias);
        line.setColor(net.md_5.bungee.api.ChatColor.GREEN);

        if (!args.isEmpty()) {
            TextComponent argsComp = new TextComponent(" " + args);
            argsComp.setColor(net.md_5.bungee.api.ChatColor.WHITE);
            line.addExtra(argsComp);
        }

        line.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, fullCmd));
        line.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("Click to suggest command")));

        player.spigot().sendMessage(line);
        player.sendMessage(ChatColor.GREEN + "      ╰ " + ChatColor.GRAY + messages.getMessageByKey(descKey));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}