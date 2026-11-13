package me.geraldr12.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import me.geraldr12.utils.ConfigAccessor;
import me.geraldr12.utils.Messages;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MessagesDefaultsTest {
    private Messages messages() throws Exception {
        FileConfiguration config = mock(FileConfiguration.class,
                withSettings().useConstructor().defaultAnswer(CALLS_REAL_METHODS));
        ConfigAccessor accessor = mock(ConfigAccessor.class);
        when(accessor.getConfig()).thenReturn(config);
        Messages messages = mock(Messages.class, CALLS_REAL_METHODS);
        var field = Messages.class.getDeclaredField("messagesConfig");
        field.setAccessible(true);
        field.set(messages, accessor);
        return messages;
    }

    @Test
    void absentNewKeyFallsBackWithoutAResourceOrServer() throws Exception {
        Messages messages = messages();
        assertEquals("Stonks", messages.getMessageByKey("uiMainMenuTitle"));
        assertEquals("§aBuy 100 shares", messages.getMessageByKey("uiDetailBuyHundred"));
        assertEquals("§4This cannot be undone!", messages.getMessageByKey("uiDeleteWarning"));
        messages.getMessagesConfig().getConfig().set("uiMainMenuTitle", "Custom title");
        assertEquals("Custom title", messages.getMessageByKey("uiMainMenuTitle"));
    }

    @Test
    void existingResourceDefaultsRemainAvailable() throws Exception {
        Messages messages = messages();
        var defaults = new org.bukkit.configuration.MemoryConfiguration();
        defaults.set("buyCommand.description", "Bundled description");
        messages.getMessagesConfig().getConfig().setDefaults(defaults);
        assertEquals("Bundled description", messages.getMessageByKey("buyCommand.description"));
    }

    @Test
    void everyNewBundledKeyHasTheSameNonNullDefault() throws Exception {
        Messages messages = messages();
        try (var stream = getClass().getResourceAsStream("/messages.yml")) {
            var bundled = new ObjectMapper(new YAMLFactory()).readTree(stream);
            int keys = 0;
            var fields = bundled.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String key = entry.getKey();
                if (!key.startsWith("uiMain") && !key.startsWith("uiDetail") && !key.startsWith("uiDelete")) continue;
                keys++;
                assertNotNull(messages.getMessageByKey(key), key);
                assertEquals(entry.getValue().asText(), messages.getMessageByKey(key), key);
            }
            assertTrue(keys > 0);
        }
    }
}
