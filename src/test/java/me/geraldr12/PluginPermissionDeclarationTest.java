package me.geraldr12;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginPermissionDeclarationTest {

    private static final Pattern NODE = Pattern.compile("\"((?:stonks|blockstreet)\\.[a-z0-9_.*-]+)\"");

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> declared() throws IOException {
        Map<String, Object> root = new Yaml().load(Files.readString(Path.of("src/main/resources/plugin.yml")));
        return (Map<String, Map<String, Object>>) root.get("permissions");
    }

    private Set<String> used() throws IOException {
        Set<String> nodes = new TreeSet<>();
        List<Path> sources;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            sources = files.filter(path -> path.toString().endsWith(".java")).toList();
        }
        for (Path source : sources) {
            Matcher matcher = NODE.matcher(Files.readString(source));
            while (matcher.find()) {
                nodes.add(matcher.group(1));
            }
        }
        return nodes;
    }

    @Test
    void scanFindsTheKnownNodes() throws IOException {
        assertTrue(used().containsAll(Set.of("stonks.use", "blockstreet.admin.reload", "stonks.ui.portfolio")));
    }

    @Test
    void everyUsedNodeIsDeclared() throws IOException {
        Set<String> missing = new TreeSet<>(used());
        missing.removeAll(declared().keySet());

        assertEquals(Set.of(), missing);
    }

    @Test
    void noWildcardNodeIsCheckedInCode() throws IOException {
        assertFalse(used().stream().anyMatch(node -> node.contains("*")), "Bukkit does not expand wildcards");
    }

    @Test
    void uiNodesFollowTheirNeighbours() throws IOException {
        for (String node : List.of("stonks.ui.companies", "stonks.ui.portfolio", "stonks.ui.notifications")) {
            assertEquals(Boolean.TRUE, declared().get(node).get("default"), node);
        }
    }

    @Test
    void adminNodesAreOperatorOnly() throws IOException {
        declared().forEach((node, values) -> {
            if (node.contains(".admin.")) {
                assertEquals("op", String.valueOf(values.get("default")), node);
            }
        });
    }
}
