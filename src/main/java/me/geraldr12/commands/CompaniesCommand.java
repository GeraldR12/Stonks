package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.utils.FormattingUtils;
import me.geraldr12.utils.Messages;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Companies command
 * Converted to standard Bukkit API via SubCommand interface.
 */
public class CompaniesCommand implements MainCommand.SubCommand {

    private final Stonks plugin;

    public CompaniesCommand(Stonks plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(Player player, String[] args) {

        Messages messages = plugin.getMessages();
        CompaniesService companiesService = plugin.getCompaniesService();

        // Check permission manually
        if (!player.hasPermission("blockstreet.command.companies")) {
            player.sendMessage(messages.getPluginPrefix() + messages.getNoPermission());
            return true;
        }

        List<CompanyDao> companiesList = companiesService.getAllCompanies();

        player.sendMessage(messages.getPluginHeader());
        companiesList.forEach(company -> printCompanyDetails(player, messages, company));
        player.sendMessage(messages.getPluginFooter());

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        return List.of();
    }

    private void printCompanyDetails(Player player, Messages messages, CompanyDao currentCompany) {

        TextComponent companyDetails = new TextComponent(ChatColor.GRAY + "[Details]");
        companyDetails.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder(ChatColor.GRAY + "Click to see company's details.").create()));
        companyDetails.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/invest company info " + currentCompany.getId()));

        if (currentCompany.getName() != null) {
            player.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + currentCompany.getName());
            player.sendMessage("");
            player.sendMessage(ChatColor.GRAY + messages.getCompanyStatus() + ": " + (currentCompany.isBankrupt() ? messages.getCompanyStatusBankrupt() : messages.getCompanyStatusTrading()));
            player.sendMessage(ChatColor.GRAY + messages.getPrice() + ": " + ChatColor.GREEN + FormattingUtils.formatDouble(currentCompany.getCurrentSharePrice()));
            player.sendMessage(ChatColor.GRAY + messages.getRisk() + ": " + ChatColor.GREEN + currentCompany.getRisk());
            player.spigot().sendMessage(companyDetails);
            player.sendMessage("");
        }
    }
}