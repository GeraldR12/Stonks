package me.geraldr12.data.services;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.dao.InvestmentDao;
import me.geraldr12.data.dao.PlayerDao;
import me.geraldr12.data.repositories.Repository;
import me.geraldr12.data.repositories.implementations.PlayersRepository;
import me.geraldr12.enums.NotificationType;
import me.geraldr12.utils.MarketTransactionLocks;
import org.bukkit.Bukkit;

import javax.inject.Inject;
import java.io.File;
import java.util.*;

public class PlayersService implements Service {

    private final PlayersRepository playersRepository;
    private final Stonks plugin;

    @Inject
    public PlayersService(PlayersRepository playersRepository, Stonks plugin) {
        this.playersRepository = playersRepository;
        this.plugin = plugin;
    }

    public boolean addSharesToPlayer(UUID playerId, CompanyDao company, int sharesAmount) {

        if (playerId == null || company == null || sharesAmount <= 0 || !Double.isFinite(company.getCurrentSharePrice()) || company.getCurrentSharePrice() <= 0) {
            return false;
        }

        synchronized (MarketTransactionLocks.player(playerId)) {
            PlayerDao playerDao = getOrCreatePlayer(playerId);
            PlayerDao previous = copyPlayer(playerDao);
            InvestmentDao recentInvestment = new InvestmentDao(company.getId(), sharesAmount, company.getCurrentSharePrice());

            boolean hasInvestedInCompany = playerDao.getInvestments().stream().anyMatch(playerInvestment -> playerInvestment.getCompanyId() == recentInvestment.getCompanyId());

            if (hasInvestedInCompany) {
                playerDao.getInvestments().stream()
                        .filter(playerInvestment -> playerInvestment.getCompanyId() == recentInvestment.getCompanyId())
                        .findFirst()
                        .ifPresent(playerInvestment -> {
                            double averageBuyPrice = calculateNewAverageBuyPrice(playerInvestment, recentInvestment);
                            playerInvestment.setSharesAmount(playerInvestment.getSharesAmount() + recentInvestment.getSharesAmount());
                            playerInvestment.setAverageBuyPrice(averageBuyPrice);
                        });
            } else {
                playerDao.getInvestments().add(recentInvestment);
            }

            boolean saved = playersRepository.save(playerDao.toEntity());
            if (!saved) {
                playerDao.getInvestments().clear();
                playerDao.getInvestments().addAll(previous.getInvestments());
            }
            return saved;
        }
    }

    public boolean removeSharesFromPlayer(UUID playerId, long companyId, int sharesAmount) {

        if (playerId == null || sharesAmount <= 0) return false;

        synchronized (MarketTransactionLocks.player(playerId)) {
            PlayerDao playerDao = getOrCreatePlayer(playerId);
            PlayerDao previous = copyPlayer(playerDao);
            Optional<InvestmentDao> investment = playerDao.getInvestments().stream()
                    .filter(playerInvestment -> playerInvestment.getCompanyId() == companyId)
                    .findFirst();
            if (investment.isEmpty() || investment.get().getSharesAmount() < sharesAmount) return false;

            if (investment.get().getSharesAmount() == sharesAmount) {
                playerDao.getInvestments().remove(investment.get());
            } else {
                investment.get().setSharesAmount(investment.get().getSharesAmount() - sharesAmount);
            }

            boolean saved = playersRepository.save(playerDao.toEntity());
            if (!saved) {
                playerDao.getInvestments().clear();
                playerDao.getInvestments().addAll(previous.getInvestments());
            }
            return saved;
        }
    }

    public ShareMutationResult addSharesToPlayerTyped(UUID playerId, long companyId, int quantity, double purchaseUnitPrice) {
        return addSharesToPlayerTyped(playerId, companyId, quantity, purchaseUnitPrice, 0);
    }

    public ShareMutationResult addSharesToPlayerTyped(UUID playerId, long companyId, int quantity,
                                                       double purchaseUnitPrice, int maxSharesPerPlayer) {
        ShareMutationOperation operation = ShareMutationOperation.PLAYER_ADD;
        if (playerId == null || quantity <= 0 || !Double.isFinite(purchaseUnitPrice) || purchaseUnitPrice <= 0) {
            return ShareMutationResult.rejected(operation, playerId, companyId, quantity, "invalid player mutation input");
        }
        synchronized (MarketTransactionLocks.player(playerId)) {
            PlayerDao player = getOrCreatePlayer(playerId);
            InvestmentDao investment = player.getInvestments().stream()
                    .filter(item -> item.getCompanyId() == companyId).findFirst().orElse(null);
            long beforeQuantity = investment == null ? 0L : investment.getSharesAmount();
            Double beforePrice = investment == null ? null : investment.getAverageBuyPrice();
            if (investment != null && (beforeQuantity <= 0 || beforePrice == null
                    || !Double.isFinite(beforePrice) || beforePrice <= 0)) {
                return ShareMutationResult.rejected(operation, playerId, companyId, quantity,
                        "existing investment state is invalid");
            }
            long afterQuantity;
            try {
                afterQuantity = Math.addExact(beforeQuantity, (long) quantity);
            } catch (ArithmeticException e) {
                return ShareMutationResult.rejected(operation, playerId, companyId, quantity, "share arithmetic overflow");
            }
            if (afterQuantity > Integer.MAX_VALUE) {
                return ShareMutationResult.rejected(operation, playerId, companyId, quantity, "share arithmetic overflow");
            }
            long totalAfter;
            try {
                totalAfter = Math.addExact(getTotalPlayerSharesCount(playerId), (long) quantity);
            } catch (ArithmeticException e) {
                return ShareMutationResult.rejected(operation, playerId, companyId, quantity, "share limit arithmetic overflow");
            }
            if (maxSharesPerPlayer > 0 && totalAfter > maxSharesPerPlayer) {
                return ShareMutationResult.rejected(operation, playerId, companyId, quantity, "player share limit exceeded");
            }
            double afterPrice;
            if (investment == null) {
                afterPrice = purchaseUnitPrice;
            } else {
                double existingValue = beforeQuantity * beforePrice;
                double newValue = quantity * purchaseUnitPrice;
                afterPrice = (existingValue + newValue) / afterQuantity;
            }
            if (!Double.isFinite(afterPrice) || afterPrice <= 0) {
                return ShareMutationResult.rejected(operation, playerId, companyId, quantity, "invalid average price");
            }
            if (investment == null) {
                player.getInvestments().add(new InvestmentDao(companyId, quantity, purchaseUnitPrice));
            } else {
                investment.setSharesAmount((int) afterQuantity);
                investment.setAverageBuyPrice(afterPrice);
            }
            try {
                if (!playersRepository.save(player.toEntity())) {
                    return ShareMutationResult.ambiguous(operation, playerId, companyId, quantity, beforeQuantity, afterQuantity,
                            purchaseUnitPrice, beforePrice, afterPrice, "persistence result unavailable");
                }
                return ShareMutationResult.confirmed(operation, playerId, companyId, quantity, beforeQuantity, afterQuantity,
                        purchaseUnitPrice, beforePrice, afterPrice);
            } catch (RuntimeException e) {
                return ShareMutationResult.ambiguous(operation, playerId, companyId, quantity, beforeQuantity, afterQuantity,
                        purchaseUnitPrice, beforePrice, afterPrice, e.getClass().getSimpleName());
            }
        }
    }

    ShareMutationResult restorePlayerSharesTyped(UUID playerId, long companyId, ConfirmedPlayerAddEvidence evidence) {
        ShareMutationOperation operation = ShareMutationOperation.PLAYER_RESTORE;
        if (playerId == null) {
            return ShareMutationResult.rejected(operation, null, companyId, 0, "player identity is required");
        }
        if (evidence == null || !playerId.equals(evidence.playerId()) || evidence.companyId() != companyId) {
            return ShareMutationResult.rejected(operation, playerId, companyId, 0, "evidence identity mismatch");
        }
        long quantity;
        try {
            quantity = Math.subtractExact(evidence.afterQuantity(), evidence.beforeQuantity());
        } catch (ArithmeticException e) {
            return ShareMutationResult.rejected(operation, playerId, companyId, 0, "evidence arithmetic overflow");
        }
        boolean absentBefore = evidence.beforeQuantity() == 0 && evidence.beforeAveragePrice() == null;
        boolean existingBefore = evidence.beforeQuantity() > 0 && evidence.beforeAveragePrice() != null
                && Double.isFinite(evidence.beforeAveragePrice()) && evidence.beforeAveragePrice() > 0;
        boolean validPrices = evidence.afterAveragePrice() != null && evidence.purchaseUnitPrice() != null
                && Double.isFinite(evidence.afterAveragePrice()) && evidence.afterAveragePrice() > 0
                && Double.isFinite(evidence.purchaseUnitPrice()) && evidence.purchaseUnitPrice() > 0;
        boolean validAverage = absentBefore
                ? Double.compare(evidence.afterAveragePrice(), evidence.purchaseUnitPrice()) == 0
                : existingBefore && Double.compare(
                        (evidence.beforeQuantity() * evidence.beforeAveragePrice()
                                + quantity * evidence.purchaseUnitPrice()) / evidence.afterQuantity(),
                        evidence.afterAveragePrice()) == 0;
        if (quantity <= 0 || evidence.beforeQuantity() < 0 || evidence.afterQuantity() <= evidence.beforeQuantity()
                || (!absentBefore && !existingBefore) || !validPrices || !validAverage
                || (absentBefore && evidence.afterQuantity() != quantity)) {
            return ShareMutationResult.rejected(operation, playerId, companyId, quantity, "invalid evidence");
        }
        synchronized (MarketTransactionLocks.player(playerId)) {
            PlayerDao player = getOrCreatePlayer(playerId);
            InvestmentDao investment = player.getInvestments().stream()
                    .filter(item -> item.getCompanyId() == companyId).findFirst().orElse(null);
            if (investment == null || investment.getSharesAmount() != evidence.afterQuantity()
                    || Double.compare(investment.getAverageBuyPrice(), evidence.afterAveragePrice()) != 0) {
                return ShareMutationResult.rejected(operation, playerId, companyId, quantity, "player state changed");
            }
            if (evidence.beforeQuantity() == 0) {
                player.getInvestments().remove(investment);
            } else {
                if (evidence.beforeQuantity() > Integer.MAX_VALUE) {
                    return ShareMutationResult.rejected(operation, playerId, companyId, quantity, "share arithmetic overflow");
                }
                investment.setSharesAmount((int) evidence.beforeQuantity());
                investment.setAverageBuyPrice(evidence.beforeAveragePrice());
            }
            try {
                if (!playersRepository.save(player.toEntity())) {
                    return ShareMutationResult.ambiguous(operation, playerId, companyId, quantity, evidence.afterQuantity(), evidence.beforeQuantity(),
                            evidence.purchaseUnitPrice(), evidence.afterAveragePrice(), evidence.beforeAveragePrice(), "persistence result unavailable");
                }
                return ShareMutationResult.confirmed(operation, playerId, companyId, quantity, evidence.afterQuantity(), evidence.beforeQuantity(),
                        evidence.purchaseUnitPrice(), evidence.afterAveragePrice(), evidence.beforeAveragePrice());
            } catch (RuntimeException e) {
                return ShareMutationResult.ambiguous(operation, playerId, companyId, quantity, evidence.afterQuantity(), evidence.beforeQuantity(),
                        evidence.purchaseUnitPrice(), evidence.afterAveragePrice(), evidence.beforeAveragePrice(), e.getClass().getSimpleName());
            }
        }
    }

    public ShareMutationResult removeSharesFromPlayerTyped(UUID playerId, long companyId, int quantity) {
        ShareMutationOperation operation = ShareMutationOperation.PLAYER_REMOVE;
        if (playerId == null || quantity <= 0) {
            return ShareMutationResult.rejected(operation, playerId, companyId, quantity, "invalid player mutation input");
        }
        synchronized (MarketTransactionLocks.player(playerId)) {
            PlayerDao player = getOrCreatePlayer(playerId);
            InvestmentDao investment = player.getInvestments().stream()
                    .filter(item -> item.getCompanyId() == companyId).findFirst().orElse(null);

            if (investment == null) {
                return ShareMutationResult.rejected(operation, playerId, companyId, quantity, "investment not found");
            }
            long beforeQuantity = investment.getSharesAmount();
            Double beforePrice = investment.getAverageBuyPrice();

            if (beforeQuantity < quantity) {
                return ShareMutationResult.rejected(operation, playerId, companyId, quantity, "insufficient shares");
            }

            if (beforeQuantity <= 0 || beforePrice == null || !Double.isFinite(beforePrice) || beforePrice <= 0) {
                return ShareMutationResult.rejected(operation, playerId, companyId, quantity, "existing investment state is invalid");
            }

            long afterQuantity = beforeQuantity - quantity;
            Double afterPrice = afterQuantity == 0 ? null : beforePrice;

            if (afterQuantity == 0) {
                player.getInvestments().remove(investment);
            } else {
                investment.setSharesAmount((int) afterQuantity);
            }

            try {
                if (!playersRepository.save(player.toEntity())) {
                    return ShareMutationResult.ambiguous(operation, playerId, companyId, quantity, beforeQuantity, afterQuantity,
                            null, beforePrice, null, "persistence result unavailable");
                }
                return ShareMutationResult.confirmed(operation, playerId, companyId, quantity, beforeQuantity, afterQuantity,
                        null, beforePrice, null);
            } catch (RuntimeException e) {
                return ShareMutationResult.ambiguous(operation, playerId, companyId, quantity, beforeQuantity, afterQuantity,
                        null, beforePrice, null, e.getClass().getSimpleName());
            }
        }
    }

    public ShareMutationResult restoreRemovedPlayerSharesTyped(ConfirmedPlayerRemovalEvidence evidence) {
        ShareMutationOperation operation = ShareMutationOperation.PLAYER_REMOVE_RESTORE;
        long requestedQuantity = evidence.beforeQuantity() - evidence.afterQuantity();

        synchronized (MarketTransactionLocks.player(evidence.playerId())) {
            PlayerDao player = getOrCreatePlayer(evidence.playerId());
            InvestmentDao investment = player.getInvestments().stream()
                    .filter(item -> item.getCompanyId() == evidence.companyId()).findFirst().orElse(null);

            long currentQuantity = investment == null ? 0 : investment.getSharesAmount();
            if (currentQuantity != evidence.afterQuantity()) {
                return ShareMutationResult.ambiguous(operation, evidence.playerId(), evidence.companyId(), requestedQuantity,
                        evidence.afterQuantity(), evidence.beforeQuantity(), null, evidence.beforeAveragePrice(),
                        null, "player state divergence preventing restoration");
            }

            if (investment == null) {
                investment = new InvestmentDao(evidence.companyId(), (int) evidence.beforeQuantity(), evidence.beforeAveragePrice());
                player.getInvestments().add(investment);
            } else {
                investment.setSharesAmount((int) evidence.beforeQuantity());
                investment.setAverageBuyPrice(evidence.beforeAveragePrice());
            }

            try {
                if (!playersRepository.save(player.toEntity())) {
                    return ShareMutationResult.ambiguous(operation, evidence.playerId(), evidence.companyId(), requestedQuantity,
                            evidence.afterQuantity(), evidence.beforeQuantity(), null, evidence.beforeAveragePrice(),
                            null, "persistence result unavailable");
                }
                return ShareMutationResult.confirmed(operation, evidence.playerId(), evidence.companyId(), requestedQuantity,
                        evidence.afterQuantity(), evidence.beforeQuantity(), null, evidence.beforeAveragePrice(), null);
            } catch (RuntimeException e) {
                return ShareMutationResult.ambiguous(operation, evidence.playerId(), evidence.companyId(), requestedQuantity,
                        evidence.afterQuantity(), evidence.beforeQuantity(), null, evidence.beforeAveragePrice(),
                        null, e.getClass().getSimpleName());
            }
        }
    }

    public boolean hasSharesInCompany(UUID playerId, long companyId, int sharesAmount) {

        PlayerDao playerDao = getOrCreatePlayer(playerId);

        return playerDao.getInvestments().stream()
                .anyMatch(investment -> investment.getCompanyId() == companyId && investment.getSharesAmount() >= sharesAmount);

    }

    public long getTotalPlayerSharesCount(UUID playerId) {
        return getOrCreatePlayer(playerId).getInvestments().stream()
                .mapToLong(InvestmentDao::getSharesAmount)
                .sum();
    }

    public double getTotalPortfolioValue(UUID playerId, CompaniesService companiesService) {
        return getOrCreatePlayer(playerId).getInvestments().stream()
                .mapToDouble(investment -> companiesService.getCompanyInvestmentValue(investment.getCompanyId(), investment.getSharesAmount()))
                .sum();
    }

    public List<InvestmentDao> getInvestments(UUID playerId) {
        return Collections.unmodifiableList(getOrCreatePlayer(playerId).getInvestments());
    }

    public Optional<InvestmentDao> getInvestmentInCompany(UUID playerId, long companyId) {
        PlayerDao playerDao = getOrCreatePlayer(playerId);
        return playerDao.getInvestments().stream()
                // Ensure both sides are compared as long to avoid precision/type mismatches
                .filter(investment -> (long) investment.getCompanyId() == companyId)
                .findFirst();
    }

    public boolean hasAnyInvestments(UUID playerId) {
        return !getInvestments(playerId).isEmpty();
    }

    /**
     * Cleans up investments for a player by removing shares from companies that no longer exist.
     *
     * @param playerId          the unique identifier of the player
     * @param existingCompanies a list containing all existing companies
     */
    public void cleanUpInvestments(UUID playerId, List<CompanyDao> existingCompanies) {
        if (!playerExists(playerId)) {
            return; // No need to clean up if the player does not exist
        }
        getInvestments(playerId).stream()
                .filter(investment -> existingCompanies.stream().noneMatch(company -> company.getId() == investment.getCompanyId()))
                .forEach(investment -> removeSharesFromPlayer(playerId, investment.getCompanyId(), investment.getSharesAmount()));
    }

    /**
     * Cleans up investments for all online players by removing shares from companies that no longer exist.
     *
     * @param existingCompanies a list containing all existing companies
     */
    public void cleanUpInvestmentsForOnlinePlayers(List<CompanyDao> existingCompanies) {
        Bukkit.getOnlinePlayers().forEach(player -> {
            UUID playerId = player.getUniqueId();
            cleanUpInvestments(playerId, existingCompanies);
        });
    }

    /**
     * Checks if a player has notifications enabled. If the setting is null, it defaults to true.
     *
     * @param playerId the unique identifier of the player
     * @return true if notifications are enabled or the setting is null, false otherwise
     */
    public boolean hasNotificationEnabled(UUID playerId, NotificationType notificationType) {
        PlayerDao playerDao = getOrCreatePlayer(playerId);
        return playerDao.getBlockedNotifications().stream()
                .noneMatch(blockedType -> blockedType == notificationType);
    }

    /**
     * Toggles the notification setting for a specific type of notification for a player.
     *
     * @param playerId         the unique identifier of the player to toggle the notification for
     * @param notificationType the type of notification to toggle
     */
    public void toggleNotification(UUID playerId, NotificationType notificationType) {
        PlayerDao playerDao = getOrCreatePlayer(playerId);
        if (hasNotificationEnabled(playerId, notificationType)) {
            playerDao.getBlockedNotifications().add(notificationType);
        } else {
            playerDao.getBlockedNotifications().removeIf(blockedType -> blockedType == notificationType);
        }
        playersRepository.save(playerDao.toEntity());
    }

    /** Updates an existing player's stored name without creating a player record. */
    public void rememberName(UUID playerId, String name) {
        if (playerId == null || name == null || name.isBlank()) return;
        synchronized (MarketTransactionLocks.player(playerId)) {
            playersRepository.getById(playerId).ifPresent(player -> {
                if (!name.equals(player.getName())) {
                    player.setName(name);
                    playersRepository.save(player);
                }
            });
        }
    }

    private double calculateNewAverageBuyPrice(InvestmentDao existingInvestment, InvestmentDao newInvestment) {
        if (existingInvestment.getAverageBuyPrice() <= 0.0) {
            return newInvestment.getAverageBuyPrice();
        }
        double existingTotalValue = existingInvestment.getSharesAmount() * existingInvestment.getAverageBuyPrice();
        double newTotalValue = newInvestment.getSharesAmount() * newInvestment.getAverageBuyPrice();
        int totalShares = existingInvestment.getSharesAmount() + newInvestment.getSharesAmount();
        return totalShares > 0 ? (existingTotalValue + newTotalValue) / totalShares : 0.0;
    }

    private boolean playerExists(UUID playerId) {
        return playersRepository.exists(playerId);
    }

    private PlayerDao copyPlayer(PlayerDao player) {
        return (PlayerDao) new PlayerDao().fromEntity(player.toEntity());
    }

    private PlayerDao getOrCreatePlayer(UUID playerId) {
        if (playerExists(playerId)) {
            return (PlayerDao) playersRepository.getById(playerId)
                    .map(playerEntity -> new PlayerDao().fromEntity(playerEntity))
                    .orElseThrow();
        } else {
            var server = plugin.getServer();
            var onlinePlayer = server == null ? null : server.getPlayer(playerId);
            String name = onlinePlayer == null ? null : onlinePlayer.getName();
            return PlayerDao.builder()
                    .name(name == null || name.isBlank() ? "Unknown" : name)
                    .uniqueId(playerId.toString())
                    .investments(new ArrayList<>())
                    .blockedNotifications(new HashSet<>())
                    .build();
        }
    }

    public Map<UUID, Integer> getShareholdersForCompany(long companyId) {
        Map<UUID, Integer> shareholders = new HashMap<>();

        // This is expensive but necessary: scan all player files
        // Note: In a large server, you'd want to cache this!
        for (File file : new File(plugin.getDataFolder(), "players").listFiles()) {
            if (!file.getName().endsWith(".yml")) continue;
            UUID uuid = UUID.fromString(file.getName().replace(".yml", ""));

            getInvestmentInCompany(uuid, companyId).ifPresent(inv -> {
                if (inv.getSharesAmount() > 0) {
                    shareholders.put(uuid, inv.getSharesAmount());
                }
            });
        }
        return shareholders;
    }

    @Override
    public Repository<?, ?> getRepository() {
        return playersRepository;
    }

}
