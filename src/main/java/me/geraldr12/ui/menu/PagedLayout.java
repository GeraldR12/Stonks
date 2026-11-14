package me.geraldr12.ui.menu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PagedLayout {
    private final List<Character> slots = new ArrayList<>();
    private final Map<Character, MenuButton> fixed;
    private final List<MenuButton> content;
    private final int capacity;

    /** Uses {@code x} slots for paged content and hides {@code <} and {@code >} buttons at page boundaries. */
    public PagedLayout(String[] rows, Map<Character, MenuButton> fixed, List<MenuButton> content) {
        if (rows.length < 1 || rows.length > 6) {
            throw new IllegalArgumentException("Menus must have between 1 and 6 rows");
        }
        for (String row : rows) {
            String symbols = row.replace(" ", "");
            if (symbols.length() != 9) {
                throw new IllegalArgumentException("Menu rows must have 9 slots");
            }
            for (char symbol : symbols.toCharArray()) slots.add(symbol);
        }
        this.fixed = Map.copyOf(fixed);
        this.content = List.copyOf(content);
        capacity = (int) slots.stream().filter(symbol -> symbol == 'x').count();
        if (capacity == 0 && !content.isEmpty()) {
            throw new IllegalArgumentException("Content requires at least one x slot");
        }
    }

    public int size() {
        return slots.size();
    }

    public int pageCount() {
        return capacity == 0 ? 1 : Math.max(1, (content.size() + capacity - 1) / capacity);
    }

    /** Returns buttons by inventory slot, clamping the zero-based page to the available range. */
    public Map<Integer, MenuButton> getPage(int requestedPage) {
        int page = Math.max(0, Math.min(requestedPage, pageCount() - 1));
        int index = page * capacity;
        Map<Integer, MenuButton> buttons = new HashMap<>();
        for (int slot = 0; slot < slots.size(); slot++) {
            char symbol = slots.get(slot);
            if (symbol == 'x') {
                if (index < content.size()) buttons.put(slot, content.get(index++));
            } else if ((symbol != '<' || page > 0) && (symbol != '>' || page < pageCount() - 1)) {
                MenuButton button = fixed.get(symbol);
                if (button != null) buttons.put(slot, button);
            }
        }
        return Map.copyOf(buttons);
    }
}
