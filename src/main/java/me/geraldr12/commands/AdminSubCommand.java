package me.geraldr12.commands;

import me.geraldr12.Stonks;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.util.Arrays;
import java.util.List;

public class AdminSubCommand implements MainCommand.SubCommand {

    private final AdminCreateCommand create;
    private final AdminDeleteCommand delete;

    public AdminSubCommand(Stonks plugin) {
        this.create = new AdminCreateCommand(plugin);
        this.delete = new AdminDeleteCommand(plugin);
    }

    @Override
    public boolean onCommand(Player player, String[] args) {
        if (args.length == 0) return false;

        String action = args[0].toLowerCase();
        // create.onCommand now matches the SubCommand interface: (Player, String[])
        if (action.equals("create")) {
            return create.onCommand(player, Arrays.copyOfRange(args, 1, args.length));
        } else if (action.equals("delete")) {
            return delete.onCommand(player, Arrays.copyOfRange(args, 1, args.length));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) return List.of("create", "delete");
        return List.of();
    }
}