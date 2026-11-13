package me.geraldr12.ui.menu;

import java.util.List;
import java.util.Objects;
import org.bukkit.Material;

public record MenuIcon(Material material, String name, List<String> lore, int amount) {
    public MenuIcon {
        Objects.requireNonNull(material);
        Objects.requireNonNull(name);
        lore = List.copyOf(lore);
        if (amount < 1 || amount > 64) {
            throw new IllegalArgumentException("Icon amount must be between 1 and 64");
        }
    }

    public MenuIcon(Material material, String name, List<String> lore) {
        this(material, name, lore, 1);
    }
}
