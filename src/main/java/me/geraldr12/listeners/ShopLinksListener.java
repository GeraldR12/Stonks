package me.geraldr12.listeners;

import me.geraldr12.data.services.ShopLinksService;
import me.geraldr12.events.CompanyBankruptEvent;
import me.geraldr12.events.CompanyDeleteEvent;
import me.geraldr12.integration.QuickShopAdapter;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.Optional;

/** Drops chest shop links when their company or their shop goes away. */
public class ShopLinksListener implements Listener {

    private final ShopLinksService links;

    public ShopLinksListener(ShopLinksService links) {
        this.links = links;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCompanyDelete(CompanyDeleteEvent event) {
        if (event.getCompany() != null) links.removeCompany(event.getCompany().getId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCompanyBankrupt(CompanyBankruptEvent event) {
        if (event.getCompany() != null) links.removeCompany(event.getCompany().getId());
    }

    /** Listens to QuickShop's ShopDeleteEvent by reflection, only when QuickShop's API is usable. */
    public void registerShopDelete(Plugin plugin, QuickShopAdapter quickShop) {
        if (quickShop == null || !quickShop.isAvailable()) return;
        try {
            Class<? extends Event> type = Class.forName(
                    "com.ghostchu.quickshop.api.event.management.ShopDeleteEvent", true, quickShop.classLoader())
                    .asSubclass(Event.class);
            Bukkit.getPluginManager().registerEvent(type, this, EventPriority.MONITOR,
                    (listener, event) -> {
                        if (event.getClass() == type) onShopDelete(event);
                    }, plugin);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            plugin.getLogger().warning("Chest shop links will not follow deleted QuickShop shops: " + e);
        }
    }

    /** Package-visible for tests: unlinks the shop carried by a (Quick)ShopDeleteEvent-shaped object. */
    void onShopDelete(Object event) {
        try {
            if (Boolean.TRUE.equals(event.getClass().getMethod("isCancelled").invoke(event))) return;
            Object phase = event.getClass().getMethod("phase").invoke(event);
            String phaseName = phase == null ? "" : phase.toString();
            if (!phaseName.equals("MAIN") && !phaseName.equals("POST")) return;
            Optional<?> shop = (Optional<?>) event.getClass().getMethod("shop").invoke(event);
            if (shop == null || shop.isEmpty()) return;
            Object id = shop.get().getClass().getMethod("getShopId").invoke(shop.get());
            links.unlink(((Number) id).longValue());
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // A changed QuickShop API must never break shop deletion.
        }
    }
}
