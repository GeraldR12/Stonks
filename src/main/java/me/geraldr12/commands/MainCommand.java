package me.geraldr12.commands;

import me.geraldr12.ui.GuiManager;
import me.geraldr12.ui.PluginGuiType;
import me.geraldr12.utils.Messages;
import dev.hugog.minecraft.dev_command.annotations.AutoValidation;
import dev.hugog.minecraft.dev_command.annotations.Command;
import dev.hugog.minecraft.dev_command.annotations.Dependencies;
import dev.hugog.minecraft.dev_command.commands.BukkitDevCommand;
import dev.hugog.minecraft.dev_command.commands.data.BukkitCommandData;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

@AutoValidation
@Command(alias = "", description = "mainCommand.description", permission = "blockstreet.command.main", isPlayerOnly = true)
@Dependencies(dependencies = {Messages.class})
@SuppressWarnings("unused")
public class MainCommand extends BukkitDevCommand {

    public MainCommand(BukkitCommandData commandData, CommandSender commandSender, String[] args) {
        super(commandData, commandSender, args);
    }

    @Override
    public void execute() {

        GuiManager guiManager = getDependency(GuiManager.class);
        guiManager.navigate((Player) getCommandSender(), PluginGuiType.COMPANIES_GUI);

    }

    @Override
    public List<String> onTabComplete(String[] strings) {
        return List.of();
    }

}
