package me.geraldr12.ui;

import me.geraldr12.ui.menu.MenuButton;
import me.geraldr12.ui.menu.MenuIcon;
import me.geraldr12.ui.menu.PagedLayout;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class PagedLayoutTest {
    private final String[] rows = {
            "# # # o # n # # #",
            "# x x x x x x x #",
            "# x x x x x x x #",
            "# x x x x x x x #",
            "# # # < # > # # #"
    };
    private final MenuButton previous = button("previous");
    private final MenuButton next = button("next");
    private final Map<Character, MenuButton> fixed = Map.of('<', previous, '>', next, '#', button("border"));

    @Test
    void thirtyItemsUseTwentyOneContentSlotsAndTwoPages() {
        List<MenuButton> items = IntStream.range(0, 30).mapToObj(i -> button("item " + i)).toList();
        PagedLayout layout = new PagedLayout(rows, fixed, items);
        assertEquals(2, layout.pageCount());
        assertEquals(45, layout.size());
        Map<Integer, MenuButton> first = layout.getPage(0);
        Map<Integer, MenuButton> second = layout.getPage(1);
        assertFalse(first.containsKey(39));
        assertSame(next, first.get(41));
        assertSame(previous, second.get(39));
        assertFalse(second.containsKey(41));
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        for (int i = 0; i < slots.length; i++) assertSame(items.get(i), first.get(slots[i]));
        for (int i = 0; i < 9; i++) assertSame(items.get(21 + i), second.get(slots[i]));
        assertFalse(second.containsKey(21));
        assertEquals(second, layout.getPage(999));
        assertEquals(first, layout.getPage(-1));
    }

    @Test
    void emptyContentStillHasOnePageWithoutArrows() {
        PagedLayout layout = new PagedLayout(rows, fixed, List.of());
        assertEquals(1, layout.pageCount());
        assertFalse(layout.getPage(0).containsKey(39));
        assertFalse(layout.getPage(0).containsKey(41));
        assertFalse(layout.getPage(0).containsKey(10));
    }

    @Test
    void invalidStructuresAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PagedLayout(new String[]{"x x"}, fixed, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new PagedLayout(new String[]{". . . . . . . . ."}, fixed, List.of(button("item"))));
    }

    private static MenuButton button(String name) {
        return new MenuButton(new MenuIcon(Material.STONE, name, List.of()), null);
    }
}
