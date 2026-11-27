package me.geraldr12.commands;

import me.geraldr12.Stonks;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;

public class CompanySubCommand implements MainCommand.SubCommand {

    private final CreateCommand create;
    private final DeleteCommand delete;
    private final CompanyCommand info;
    private final ShopLinkCommand shop;
    private final CompanyFundsCommand funds;

    public CompanySubCommand(Stonks plugin) {
        this.create = new CreateCommand(plugin);
        this.delete = new DeleteCommand(plugin);
        this.info = new CompanyCommand(plugin);
        this.shop = new ShopLinkCommand(plugin);
        this.funds = new CompanyFundsCommand(plugin);
    }

    @Override
    public boolean onCommand(Player player, String[] args) {
        if (args.length == 0) return false;

        String action = args[0].toLowerCase();
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

        if (action.equals("create")) {
            return create.onCommand(player, subArgs);
        } else if (action.equals("delete")) {
            return delete.onCommand(player, subArgs);
        } else if (action.equals("info")) {
            return info.onCommand(player, subArgs);
        } else if (action.equals("shop")) {
            return shop.onCommand(player, subArgs);
        } else if (action.equals("funds")) {
            return funds.funds(player, subArgs);
        } else if (action.equals("deposit")) {
            return funds.deposit(player, subArgs);
        } else if (action.equals("dividend")) {
            return funds.dividend(player, subArgs);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return List.of("create", "delete", "info", "shop", "funds", "deposit", "dividend");
        }
        String action = args[0].toLowerCase();
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

        if (action.equals("create")) return create.onTabComplete(sender, subArgs);
        if (action.equals("delete")) return delete.onTabComplete(sender, subArgs);
        if (action.equals("info")) return info.onTabComplete(sender, subArgs);
        if (action.equals("shop")) return shop.onTabComplete(sender, subArgs);
        if ((action.equals("deposit") || action.equals("dividend") || action.equals("funds")) && subArgs.length == 1) {
            return funds.ownedCompanyIds(sender);
        }

        return List.of();
    }
}