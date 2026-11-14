package me.geraldr12.ui;

import me.geraldr12.ui.items.CompanyItem;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanyClickMappingTest {
    @Test
    void buyQuantitiesMatchExistingMouseControls() {
        for (ClickType type : ClickType.values()) {
            int expected = switch (type) {
                case RIGHT -> 1;
                case SHIFT_RIGHT -> 10;
                case SHIFT_LEFT -> 100;
                default -> 0;
            };
            assertEquals(expected, CompanyItem.sharesForClick(type), type.name());
        }
    }
}
