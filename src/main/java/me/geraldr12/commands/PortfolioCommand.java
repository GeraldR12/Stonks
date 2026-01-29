package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.utils.Messages;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.DecimalFormat;
import java.util.List;

/**
 * Portfolio Command
 * Converted to standard Bukkit API via SubCommand interface.
 */
public class PortfolioCommand implements MainCommand.SubCommand {

    private final Stonks plugin;

    public PortfolioCommand(Stonks plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(Player player, String[] args) {

        Messages messages = plugin.getMessages();
        PlayersService playersService = plugin.getPlayersService();
        CompaniesService companiesService = plugin.getCompaniesService();

        // Check permission manually
        if (!player.hasPermission("blockstreet.command.portfolio")) {
            player.sendMessage(messages.getPluginPrefix() + messages.getNoPermission());
            return true;
        }

        if (!playersService.hasAnyInvestments(player.getUniqueId())) {
            player.sendMessage(messages.getPluginPrefix() + messages.getPlayerAnyActions());
            return true;
        }

        player.sendMessage(messages.getPluginHeader());
        playersService.getInvestments(player.getUniqueId())
                .forEach(investment -> {

                    CompanyDao investedCompany = companiesService.getCompanyById(investment.getCompanyId());

                    if (investedCompany == null) return;

                    TextComponent clickableCompanyDetails = new TextComponent(ChatColor.GREEN + "[Details]");
                    clickableCompanyDetails.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            new ComponentBuilder(ChatColor.GRAY + "Click to see company's details.").create()));
                    clickableCompanyDetails.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/invest company info " + investedCompany.getId()));

                    DecimalFormat df = new DecimalFormat("#.##");

                    player.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + investedCompany.getName());
                    player.sendMessage("");
                    player.sendMessage(ChatColor.GRAY + messages.getShares() + ": " + ChatColor.GREEN + investment.getSharesAmount());
                    player.sendMessage(ChatColor.GRAY + messages.getTotalSharesValue() + ": " + ChatColor.GREEN + df.format(companiesService.getCompanyInvestmentValue(investment.getCompanyId(), investment.getSharesAmount())));
                    player.spigot().sendMessage(clickableCompanyDetails);
                    player.sendMessage("");

                });
        player.sendMessage(messages.getPluginFooter());
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}