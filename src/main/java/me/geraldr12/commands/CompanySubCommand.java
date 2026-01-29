package me.geraldr12.commands;

import me.geraldr12.Stonks;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;

public class CompanySubCommand implements MainCommand.SubCommand {

    private final CreateCommand create;
    private final DeleteCommand delete;
    private final CompanyCommand info; // Assuming you converted CompanyCommand for 'info'

    public CompanySubCommand(Stonks plugin) {
        this.create = new CreateCommand(plugin);
        this.delete = new DeleteCommand(plugin);
        this.info = new CompanyCommand(plugin);
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
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return List.of("create", "delete", "info");
        }
        String action = args[0].toLowerCase();
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

        if (action.equals("create")) return create.onTabComplete(sender, subArgs);
        if (action.equals("delete")) return delete.onTabComplete(sender, subArgs);
        if (action.equals("info")) return info.onTabComplete(sender, subArgs);

        return List.of();
    }
}