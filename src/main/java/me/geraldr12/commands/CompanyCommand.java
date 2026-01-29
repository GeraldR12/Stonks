package me.geraldr12.commands;

import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.dao.QuoteDao;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.utils.FormattingUtils;
import me.geraldr12.utils.Messages;
import dev.hugog.minecraft.dev_command.annotations.*;
import dev.hugog.minecraft.dev_command.arguments.parsers.IntegerArgumentParser;
import dev.hugog.minecraft.dev_command.commands.BukkitDevCommand;
import dev.hugog.minecraft.dev_command.commands.data.BukkitCommandData;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import me.geraldr12.data.dao.InvestmentDao;


import java.text.DecimalFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Company Command
 * <p>
 * Command which allow players to get details about a company.
 * <p>
 * Syntax: /invest company [ID]
 *
 * @author Hugo1307
 * @since v1.0.0
 */
@AutoValidation
@Command(alias = "company info", description = "companyCommand.description", permission = "blockstreet.command.company", isPlayerOnly = true)
@Arguments(
        @Argument(name = "companyId", description = "companyCommand.companyIdArg", position = 0, parser = IntegerArgumentParser.class)
)
@Dependencies(dependencies = {Messages.class, CompaniesService.class, PlayersService.class})
public class CompanyCommand extends BukkitDevCommand {

    public CompanyCommand(BukkitCommandData command, CommandSender commandSender, String[] args) {
        super(command, commandSender, args);
    }

    @Override
    public void execute() {
        // You MUST cast the dependencies like this: (Messages) ...
        Messages messages = (Messages) getDependency(Messages.class);
        CompaniesService companiesService = (CompaniesService) getDependency(CompaniesService.class);
        PlayersService playersService = (PlayersService) getDependency(PlayersService.class);
        Player player = (Player) getCommandSender();

        long companyId = Long.parseLong(getArgs()[0]);
        CompanyDao companyDao = companiesService.getCompanyById(companyId);

        if (companyDao == null) {
            player.sendMessage(messages.getPluginPrefix() + messages.getInvalidCompany());
            return;
        }

        printCompanyDetails(companyDao, player, messages, playersService);
    }

    @Override
    public List<String> onTabComplete(String[] args) {
        if (args.length == 1) {
            CompaniesService companiesService = getDependency(CompaniesService.class);
            return companiesService.getAllCompanies().stream()
                    .map(CompanyDao::getId)
                    .map(String::valueOf)
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    private void printCompanyDetails(CompanyDao currentCompany, Player player, Messages messages, PlayersService playersService) {

        // 1. Create the Buy Button
        TextComponent buyStocks = new TextComponent(ChatColor.GRAY + "[Buy Stocks] ");
        buyStocks.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder(ChatColor.GREEN + "Click to buy 1 share").create()));
        buyStocks.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/invest buy " + currentCompany.getId() + " 1"));

        // 2. Create the Sell Button
        TextComponent sellStocks = new TextComponent(ChatColor.GRAY + "[Sell Stocks]");
        sellStocks.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder(ChatColor.RED + "Click to sell 1 share").create()));
        sellStocks.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/invest sell " + currentCompany.getId() + " 1"));

        player.sendMessage(messages.getPluginHeader());
        player.sendMessage("");
        player.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + currentCompany.getName());
        player.sendMessage("");

        // --- FIXED PROFIT CALCULATION ---
        // Use getInvestmentInCompany instead of getPlayerById
        Optional<InvestmentDao> investmentOptional = playersService.getInvestmentInCompany(player.getUniqueId(), currentCompany.getId());

        if (investmentOptional.isPresent()) {
            InvestmentDao investment = investmentOptional.get();
            // Use the correct field names: getSharesAmount() and getAverageBuyPrice()
            double currentTotalValue = currentCompany.getCurrentSharePrice() * investment.getSharesAmount();
            double totalPaid = investment.getAverageBuyPrice() * investment.getSharesAmount();
            double profit = currentTotalValue - totalPaid;

            ChatColor profitColor = profit >= 0 ? ChatColor.GREEN : ChatColor.RED;
            String sign = profit >= 0 ? "+" : "";

            player.sendMessage(ChatColor.GRAY + "Your Shares: " + ChatColor.GREEN + investment.getSharesAmount());
            player.sendMessage(ChatColor.GRAY + "Profit/Loss: " + profitColor + sign + FormattingUtils.formatDouble(profit));
            player.sendMessage("");
        }

        player.sendMessage(ChatColor.GRAY + "Id: " + ChatColor.GREEN + currentCompany.getId());
        player.sendMessage(ChatColor.GRAY + messages.getCompanyStatus() + ": " + (currentCompany.isBankrupt() ? messages.getCompanyStatusBankrupt() : messages.getCompanyStatusTrading()));
        player.sendMessage(ChatColor.GRAY + messages.getPrice() + ": " + ChatColor.GREEN + FormattingUtils.formatDouble(currentCompany.getCurrentSharePrice()));
        player.sendMessage(ChatColor.GRAY + messages.getRisk() + ": " + ChatColor.GREEN + currentCompany.getRisk());

        if (currentCompany.getAvailableShares() < 0)
            player.sendMessage(ChatColor.GRAY + messages.getAvailableActions() + ": " + ChatColor.GREEN + "Unlimited");
        else
            player.sendMessage(ChatColor.GRAY + messages.getAvailableActions() + ": " + ChatColor.GREEN + currentCompany.getAvailableShares());

        player.sendMessage(ChatColor.GRAY + messages.getActionHistoric() + ": ");
        player.sendMessage("");

        List<QuoteDao> quotesToPresent = currentCompany.getHistoric().toList().subList(0, Math.min(currentCompany.getHistoric().size(), 5));
        for (QuoteDao quote : quotesToPresent) {
            DecimalFormat df = new DecimalFormat("###.###%");
            if (quote.getVariation() > 0) {
                player.sendMessage(ChatColor.GREEN + "  +" + df.format(quote.getVariation()));
            } else {
                player.sendMessage(ChatColor.RED + "  " + df.format(quote.getVariation()));
            }
        }

        player.sendMessage("");

        // Combine buttons on one line
        TextComponent actionLine = new TextComponent("");
        actionLine.addExtra(buyStocks);
        actionLine.addExtra(sellStocks);
        player.spigot().sendMessage(actionLine);

        player.sendMessage(messages.getPluginFooter());
    }

}