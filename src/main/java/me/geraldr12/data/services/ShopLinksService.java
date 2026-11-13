package me.geraldr12.data.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Which chest shop belongs to which company, stored in {@code shops/links.yml}.
 * Every change is written to disk at once (temporary file, then an atomic move).
 * Linking has no economic effect by itself.
 */
public class ShopLinksService {

    public enum LinkResult { LINKED, ALREADY_LINKED, LINKED_ELSEWHERE }

    public record ShopLink(long shopId, long companyId, String ownerUuid, long linkedAt) {
    }

    private static final Logger LOG = Logger.getLogger("Stonks");

    private final Path file;
    private final Map<Long, ShopLink> links = new LinkedHashMap<>();

    public ShopLinksService(Path file) {
        this.file = file;
        load();
    }

    public synchronized LinkResult link(long shopId, long companyId, UUID owner) {
        ShopLink existing = links.get(shopId);
        if (existing != null) {
            return existing.companyId() == companyId ? LinkResult.ALREADY_LINKED : LinkResult.LINKED_ELSEWHERE;
        }
        links.put(shopId, new ShopLink(shopId, companyId, owner.toString(), System.currentTimeMillis()));
        save();
        return LinkResult.LINKED;
    }

    public synchronized boolean unlink(long shopId) {
        if (links.remove(shopId) == null) return false;
        save();
        return true;
    }

    public synchronized Optional<ShopLink> linkOf(long shopId) {
        return Optional.ofNullable(links.get(shopId));
    }

    public synchronized Optional<Long> companyOf(long shopId) {
        return linkOf(shopId).map(ShopLink::companyId);
    }

    public synchronized List<ShopLink> linksOf(long companyId) {
        List<ShopLink> result = new ArrayList<>();
        for (ShopLink link : links.values()) {
            if (link.companyId() == companyId) result.add(link);
        }
        result.sort(Comparator.comparingLong(ShopLink::shopId));
        return result;
    }

    /** Drops every link of a deleted or bankrupt company. Returns how many were removed. */
    public synchronized int removeCompany(long companyId) {
        int before = links.size();
        links.values().removeIf(link -> link.companyId() == companyId);
        int removed = before - links.size();
        if (removed > 0) save();
        return removed;
    }

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private void load() {
        if (!Files.isRegularFile(file)) return;
        Map<String, Map<String, Map<String, Object>>> root;
        try {
            root = YAML.readValue(file.toFile(), new TypeReference<>() { });
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Unable to read " + file + "; starting with no chest shop links.", e);
            return;
        }
        Map<String, Map<String, Object>> section = root == null ? null : root.get("links");
        if (section == null) return;
        for (Map.Entry<String, Map<String, Object>> entry : section.entrySet()) {
            try {
                long shopId = Long.parseLong(entry.getKey());
                Map<String, Object> value = entry.getValue();
                links.put(shopId, new ShopLink(shopId, ((Number) value.get("company")).longValue(),
                        String.valueOf(value.getOrDefault("owner", "")),
                        value.get("linkedAt") instanceof Number n ? n.longValue() : 0L));
            } catch (RuntimeException ignored) {
                LOG.warning("Ignoring invalid chest shop link: " + entry.getKey());
            }
        }
    }

    private void save() {
        Map<String, Map<String, Object>> section = new LinkedHashMap<>();
        for (ShopLink link : links.values()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("company", link.companyId());
            value.put("owner", link.ownerUuid());
            value.put("linkedAt", link.linkedAt());
            section.put(Long.toString(link.shopId()), value);
        }
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "links", ".tmp");
            Files.writeString(temporary, YAML.writeValueAsString(Map.of("links", section)), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Unable to save chest shop links to " + file, e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }
}
