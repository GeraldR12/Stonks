package me.geraldr12.ui.guis;

import java.text.MessageFormat;
import java.util.List;
import java.util.Map;
import me.geraldr12.commands.DeleteCommand;
import me.geraldr12.commands.MainCommand.SubCommand;
import me.geraldr12.ui.AbstractPluginGui;
import me.geraldr12.ui.GuiManager;
import me.geraldr12.ui.menu.MenuButton;
import me.geraldr12.ui.menu.MenuIcon;
import me.geraldr12.ui.menu.PagedLayout;
import me.geraldr12.utils.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public class ConfirmDeleteGui extends AbstractPluginGui {
    private final long companyId;
    private final SubCommand delete;

    public ConfirmDeleteGui(Player player, GuiManager guiManager, Messages messages, long companyId) {
        this(player, guiManager, messages, companyId, new DeleteCommand(guiManager.getPlugin()));
    }

    public ConfirmDeleteGui(Player player, GuiManager guiManager, Messages messages, long companyId, SubCommand delete) {
        super(messages.getMessageByKey("uiDeleteTitle"), null, player, guiManager, messages);
        this.companyId = companyId;
        this.delete = delete;
    }

    @Override
    public PagedLayout build() {
        var company = guiManager.getCompaniesService().getCompanyById(companyId);
        String name = company != null ? company.getName() : String.valueOf(companyId);
        Map<Character, MenuButton> buttons = Map.of(
                'i', new MenuButton(new MenuIcon(Material.BARRIER,
                        MessageFormat.format(messages.getMessageByKey("uiDeleteCompanyName"), name),
                        List.of(messages.getMessageByKey("uiDeleteWarning"))), null),
                'y', new MenuButton(new MenuIcon(Material.LIME_CONCRETE,
                        messages.getMessageByKey("uiDeleteConfirm"), List.of()), (who, click) -> {
                    guiManager.close(who);
                    delete.onCommand(who, new String[]{String.valueOf(companyId)});
                }),
                'n', new MenuButton(new MenuIcon(Material.RED_CONCRETE,
                        messages.getMessageByKey("uiDeleteCancel"), List.of()),
                        (who, click) -> guiManager.navigateBack(who)));
        return new PagedLayout(new String[]{
                ". . . . i . . . .",
                ". . y . . . n . .",
                ". . . . . . . . ."
        }, buttons, List.of());
    }
}
