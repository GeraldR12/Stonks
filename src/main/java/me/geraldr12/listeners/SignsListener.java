package me.geraldr12.listeners;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.dao.InvestmentDao;
import me.geraldr12.data.dao.SignDao;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.data.services.SignsService;
import me.geraldr12.utils.Messages;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import javax.inject.Inject;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class SignsListener implements Listener {

    private final Stonks plugin;
    private final SignsService signsService;
    private final CompaniesService companiesService;
    private final PlayersService playersService;

    @Inject
    public SignsListener(Stonks plugin, SignsService signsService, CompaniesService companiesService, PlayersService playersService) {
        this.plugin = plugin;
        this.signsService = signsService;
        this.companiesService = companiesService;
        this.playersService = playersService;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null || !(clickedBlock.getState() instanceof Sign)) return;

        SignDao signAtLocation = signsService.getSignByLocation(clickedBlock.getLocation());
        if (signAtLocation == null) return;

        Player player = event.getPlayer();
        CompanyDao company = companiesService.getCompanyById(signAtLocation.getCompanyId());

        if (company == null) return;

        Messages messages = plugin.getMessages();
        DecimalFormat df = new DecimalFormat("#.##");

        // 1. Market Stats
        double variation = !company.getHistoric().isEmpty() ? company.getHistoric().peek().getVariation() * 100 : 0;
        ChatColor varColor = variation >= 0 ? ChatColor.GREEN : ChatColor.RED;

        player.sendMessage(messages.getPluginHeader());
        player.sendMessage(ChatColor.GOLD + "Company: " + ChatColor.WHITE + company.getName() + ChatColor.GRAY + " (ID: " + company.getId() + ")");
        player.sendMessage(ChatColor.GRAY + "Market Price: " + ChatColor.GREEN + "$" + df.format(company.getCurrentSharePrice()) +
                ChatColor.GRAY + " (" + varColor + (variation >= 0 ? "+" : "") + df.format(variation) + "%" + ChatColor.GRAY + ")");

        // 2. Personal Profit/Loss Calculation
        Optional<InvestmentDao> investmentOpt = playersService.getInvestmentInCompany(player.getUniqueId(), (long) company.getId());

        if (investmentOpt.isPresent() && investmentOpt.get().getSharesAmount() > 0) {
            InvestmentDao investment = investmentOpt.get();
            double currentVal = company.getCurrentSharePrice() * investment.getSharesAmount();
            double paidVal = investment.getAverageBuyPrice() * investment.getSharesAmount();
            double totalProfit = currentVal - paidVal;

            ChatColor profitColor = totalProfit > 0 ? ChatColor.GREEN : (totalProfit < 0 ? ChatColor.RED : ChatColor.GRAY);
            String prefix = totalProfit > 0 ? "+$" : (totalProfit < 0 ? "-$" : "$");

            player.sendMessage(ChatColor.GRAY + "Your Holdings: " + ChatColor.WHITE + investment.getSharesAmount() + " shares");
            player.sendMessage(ChatColor.GRAY + "Your Profit/Loss: " + profitColor + prefix + df.format(Math.abs(totalProfit)) +
                    ChatColor.GRAY + " (Avg: $" + df.format(investment.getAverageBuyPrice()) + ")");
        } else {
            player.sendMessage(ChatColor.GRAY + "Your Holdings: " + ChatColor.DARK_GRAY + "No active investment found.");
        }

        String available = company.getAvailableShares() == -1 ? "Unlimited" : String.valueOf(company.getAvailableShares());
        player.sendMessage(ChatColor.GRAY + "Stocks Left: " + ChatColor.WHITE + available);
        player.sendMessage("");

        // 3. Interactive Buttons (Using Bungee API for Spigot)
        TextComponent buyBtn = new TextComponent("  [ BUY STOCK ]  ");
        buyBtn.setColor(net.md_5.bungee.api.ChatColor.GREEN);
        buyBtn.setBold(true);
        buyBtn.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/invest buy " + company.getId() + " "));
        buyBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder(ChatColor.GRAY + "Click to prepare a buy order for " + ChatColor.GREEN + company.getName()).create()));

        TextComponent sellBtn = new TextComponent("  [ SELL STOCK ]  ");
        sellBtn.setColor(net.md_5.bungee.api.ChatColor.RED);
        sellBtn.setBold(true);
        sellBtn.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/invest sell " + company.getId() + " "));
        sellBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder(ChatColor.GRAY + "Click to prepare a sell order for " + ChatColor.RED + company.getName()).create()));

        TextComponent actionLine = new TextComponent("");
        actionLine.addExtra(buyBtn);
        actionLine.addExtra(new TextComponent("    ")); // Spacer
        actionLine.addExtra(sellBtn);

        player.spigot().sendMessage(actionLine);
        player.sendMessage(messages.getPluginFooter());
    }

    @EventHandler
    public void onSignChanged(SignChangeEvent event) {
        List<String> lines = Arrays.asList(event.getLines());
        if (lines.get(0) == null) return;

        if (lines.get(0).equalsIgnoreCase("[Stonks]")) {
            List<CompanyDao> allCompanies = companiesService.getAllCompanies();
            for (CompanyDao company : allCompanies) {
                if (company.getName().equalsIgnoreCase(lines.get(1))) {
                    Sign sign = (Sign) event.getBlock().getState();
                    SignDao signDao = signsService.createSign(company.getId(), sign.getLocation());
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> signsService.updateBukkitSignsById(signDao.getId()), 20L);
                }
            }
        }
    }

    @EventHandler
    public void onSignDestroyed(BlockBreakEvent event) {
        Block destroyedBlock = event.getBlock();
        if (destroyedBlock.getState() instanceof Sign) {
            Sign sign = (Sign) event.getBlock().getState();
            if (sign.getLines()[0].contains("Stonks")) {
                SignDao signDao = signsService.getSignByLocation(destroyedBlock.getLocation());
                if (signDao != null) {
                    signsService.deleteSign(signDao.getId());
                }
            }
        }
    }
}