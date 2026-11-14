package me.geraldr12.data.services;

import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.dao.LocationDao;
import me.geraldr12.data.dao.SignDao;
import me.geraldr12.data.entities.SignEntity;
import me.geraldr12.data.repositories.Repository;
import me.geraldr12.data.repositories.implementations.SignsRepository;
import me.geraldr12.utils.FormattingUtils;
import me.geraldr12.utils.Messages;
import me.geraldr12.utils.VisualizationUtils;
import lombok.extern.log4j.Log4j2;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Sign;

import javax.inject.Inject;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Log4j2
public class SignsService implements Service {

    public final SignsRepository repository;
    public final CompaniesService companiesService;
    private final Messages messages;

    @Inject
    public SignsService(SignsRepository repository, CompaniesService companiesService, Messages messages) {
        this.repository = repository;
        this.companiesService = companiesService;
        this.messages = messages;
    }

    public List<SignDao> getAllSigns() {

        return repository.getAllIds().stream()
                .map(repository::getById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(signEntity -> (SignDao) new SignDao().fromEntity(signEntity))
                .collect(Collectors.toList());

    }

    public SignDao createSign(long companyId, Location signLocation) {

        // One sign per block: a sign placed where an old one was replaces its record.
        deleteSignsAt(signLocation);

        SignDao signDao = SignDao.builder()
                .id(repository.getNextId().intValue())
                .companyId(companyId)
                .location(LocationDao.fromBukkitLocation(signLocation))
                .build();

        repository.save(signDao.toEntity());
        invalidateIndex();

        return signDao;

    }

    public void deleteSign(int signId) {
        repository.getById(signId).ifPresent(signEntity -> repository.delete(signEntity.getId()));
        invalidateIndex();
    }

    /** The sign registered at this block. If older versions left duplicates, the newest is kept and the rest removed. */
    public SignDao getSignByLocation(Location location) {
        if (location == null || location.getWorld() == null) return null;
        List<SignDao> here = getSignsAtBlock(location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
        if (here.isEmpty()) return null;
        SignDao newest = here.stream().max(Comparator.comparingInt(SignDao::getId)).get();
        here.stream().filter(sign -> sign.getId() != newest.getId()).forEach(sign -> deleteSign(sign.getId()));
        return newest;
    }

    // In-memory index of where signs are, so chunk loads and clicks on ordinary signs never read the sign files.
    private Set<String> blockIndex;
    private Set<String> chunkIndex;

    private static String blockKey(String world, int x, int y, int z) {
        return world + ':' + x + ':' + y + ':' + z;
    }

    private static String chunkKey(String world, int chunkX, int chunkZ) {
        return world + ':' + chunkX + ':' + chunkZ;
    }

    private synchronized void ensureIndex() {
        if (blockIndex != null) return;
        Set<String> blocks = new HashSet<>();
        Set<String> chunks = new HashSet<>();
        for (SignDao sign : getAllSigns()) {
            LocationDao l = sign.getLocation();
            if (l == null) continue;
            blocks.add(blockKey(l.getWorld(), (int) Math.floor(l.getX()), (int) Math.floor(l.getY()), (int) Math.floor(l.getZ())));
            chunks.add(chunkKey(l.getWorld(), blockToChunk(l.getX()), blockToChunk(l.getZ())));
        }
        blockIndex = blocks;
        chunkIndex = chunks;
    }

    private synchronized void invalidateIndex() {
        blockIndex = null;
        chunkIndex = null;
    }

    public synchronized boolean hasSignAt(String world, int x, int y, int z) {
        ensureIndex();
        return blockIndex.contains(blockKey(world, x, y, z));
    }

    public synchronized boolean hasSignsInChunk(String world, int chunkX, int chunkZ) {
        ensureIndex();
        return chunkIndex.contains(chunkKey(world, chunkX, chunkZ));
    }

    public List<SignDao> getSignsAtBlock(String world, int x, int y, int z) {
        if (!hasSignAt(world, x, y, z)) return List.of();
        return getAllSigns().stream()
                .filter(sign -> sameBlock(sign.getLocation(), world, x, y, z))
                .collect(Collectors.toList());
    }

    /** Removes every sign registered at this block, whatever is written on it. Returns how many were removed. */
    public int deleteSignsAt(Location location) {
        if (location == null || location.getWorld() == null) return 0;
        List<SignDao> here = getSignsAtBlock(location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
        here.forEach(sign -> deleteSign(sign.getId()));
        return here.size();
    }

    public List<SignDao> getSignsInChunk(String world, int chunkX, int chunkZ) {
        if (!hasSignsInChunk(world, chunkX, chunkZ)) return List.of();
        return getAllSigns().stream()
                .filter(sign -> sign.getLocation() != null && Objects.equals(sign.getLocation().getWorld(), world)
                        && blockToChunk(sign.getLocation().getX()) == chunkX
                        && blockToChunk(sign.getLocation().getZ()) == chunkZ)
                .collect(Collectors.toList());
    }

    static boolean sameBlock(LocationDao location, String world, int x, int y, int z) {
        return location != null && Objects.equals(location.getWorld(), world)
                && (int) Math.floor(location.getX()) == x
                && (int) Math.floor(location.getY()) == y
                && (int) Math.floor(location.getZ()) == z;
    }

    static int blockToChunk(double coordinate) {
        return ((int) Math.floor(coordinate)) >> 4;
    }

    /**
     * The sign block for this record, or null. Never loads a chunk: signs in unloaded chunks are skipped and
     * refreshed when their chunk loads. A record whose block is no longer a sign (exploded, burnt, replaced)
     * is removed.
     */
    public Sign getBukkitSignFromId(int id) {

        if (!repository.exists(id)) return null;

        Optional<SignEntity> signEntityOptional = repository.getById(id);

        if (signEntityOptional.isEmpty()) return null;

        SignDao signDao = (SignDao) new SignDao().fromEntity(signEntityOptional.get());
        Location signLocation = signDao.getLocation().toBukkitLocation();
        World world = signLocation.getWorld();
        if (world == null) return null;
        if (!world.isChunkLoaded(blockToChunk(signLocation.getX()), blockToChunk(signLocation.getZ()))) return null;

        if (signLocation.getBlock().getState() instanceof Sign sign) {
            return sign;
        }

        deleteSign(id);
        return null;

    }

    public void updateBukkitSignsById(int signId) {

        SignEntity signEntity = repository.getById(signId).orElse(null);

        if (signEntity == null) return;

        Sign bukkitSign = getBukkitSignFromId(signId);

        if (bukkitSign == null) return;

        if (companiesService.companyExists(signEntity.getCompanyId())) {

            CompanyDao companyInSign = companiesService.getCompanyById(signEntity.getCompanyId());
            updateSign(bukkitSign, companyInSign);

        }

    }

    public void updateBukkitSignsByCompany(long companyId) {

        repository.getSignsByCompanyId(companyId).stream()
                .map(signEntity -> getBukkitSignFromId(signEntity.getId()))
                .filter(Objects::nonNull)
                .forEach(bukkitSign -> {

                    if (companiesService.companyExists(companyId)) {

                        CompanyDao companyInSign = companiesService.getCompanyById(companyId);
                        updateSign(bukkitSign, companyInSign);

                    }

                });

    }

    private void updateSign(Sign sign, CompanyDao company) {
        // Calculate the percentage change from the last update
        double lastVariation = !company.getHistoric().isEmpty() ? company.getHistoric().peek().getVariation() * 100 : 0.0;

        // Line 1: Company Name
        sign.setLine(0, ChatColor.BOLD + "" + ChatColor.YELLOW + company.getName());

        // Line 2: Current Value
        sign.setLine(1, ChatColor.GREEN + "Price: " + ChatColor.GRAY + FormattingUtils.formatDouble(company.getCurrentSharePrice()));

        // Line 3: Percentage Change (formatted with arrows)
        sign.setLine(2, VisualizationUtils.formatCompanyVariation(lastVariation));

        // Line 4: Status (Trading or Bankrupt)
        if (company.isBankrupt()) {
            sign.setLine(3, messages.getCompanyStatusBankrupt());
        } else {
            sign.setLine(3, messages.getCompanyStatusTrading());
        }

        sign.setEditable(false);
        sign.update();
    }

    @Override
    public Repository<?, ?> getRepository() {
        return this.repository;
    }

}
