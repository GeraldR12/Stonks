package me.geraldr12.integration;

import org.bukkit.Location;

import java.util.Optional;

/** Finds chest shops. Implementations never throw; anything unavailable comes back empty. */
public interface ShopLookup {
    boolean isAvailable();

    Optional<QuickShopAdapter.ShopInfo> shopAt(Location location);

    Optional<QuickShopAdapter.ShopInfo> shopById(long shopId);
}
