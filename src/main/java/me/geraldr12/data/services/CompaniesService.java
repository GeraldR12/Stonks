package me.geraldr12.data.services;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.dao.QuoteDao;
import me.geraldr12.data.repositories.Repository;
import me.geraldr12.data.repositories.implementations.CompaniesRepository;
import me.geraldr12.events.CompanyCreateEvent;
import me.geraldr12.events.CompanyDeleteEvent;
import me.geraldr12.events.CompanyStockUpdateEvent;
import me.geraldr12.utils.SizedStack;
import me.geraldr12.utils.MarketTransactionLocks;
import org.bukkit.Bukkit;
import org.bukkit.Material;

import javax.inject.Inject;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class CompaniesService implements Service {

    private final Stonks plugin;
    private final CompaniesRepository companiesRepository;
    private final java.util.Set<String> warnedMarketSettings = new java.util.HashSet<>();

    @Inject
    public CompaniesService(Stonks plugin, CompaniesRepository companiesRepository) {
        this.plugin = plugin;
        this.companiesRepository = companiesRepository;
    }

    public List<CompanyDao> getAllCompanies() {
        return companiesRepository.getAllIds().stream()
                .map(companiesRepository::getById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(companyEntity -> (CompanyDao) new CompanyDao().fromEntity(companyEntity))
                .collect(Collectors.toUnmodifiableList());
    }

    public boolean companyNameExists(String name) {
        if (name == null) return false;
        String normalizedName = name.trim().toLowerCase(Locale.ROOT);
        return getAllCompanies().stream()
                .anyMatch(company -> company.getName() != null
                        && company.getName().trim().toLowerCase(Locale.ROOT).equals(normalizedName));
    }

    public CompanyDao getCompanyById(long companyId) {
        return (CompanyDao) companiesRepository.getById(companyId)
                .map(companyEntity -> new CompanyDao().fromEntity(companyEntity))
                .orElse(null);
    }

    // Change the signature to include the UUID
    // Update the signature to accept the UUID
    public CompanyDao createPlayerCompany(String companyName, int companyRisk, int companyShares, double companySharePrice,
                                          Material companyIcon, String ownerUuid) {
        return this.createCompany(CompanyDao.builder()
                .name(companyName)
                .icon(companyIcon)
                .risk(companyRisk)
                .totalShares(companyShares)
                .availableShares(companyShares)
                .initialSharePrice(companySharePrice)
                .currentSharePrice(companySharePrice)
                .historic(new SizedStack<>(500))
                .ownerUuid(ownerUuid) // ADD THIS LINE HERE
                .build()
        );
    }

    public CompanyDao createAdminCompany(String companyName, int companyRisk, int companyShares, double companySharePrice,
                                         Material companyIcon) {
        return this.createCompany(CompanyDao.builder()
                .name(companyName)
                .icon(companyIcon)
                .risk(companyRisk)
                .totalShares(companyShares)
                .availableShares(companyShares - 1) // Reserve one share for the server, preventing players to assume full control over the company
                .initialSharePrice(companySharePrice)
                .currentSharePrice(companySharePrice)
                .historic(new SizedStack<>(500))
                .build()
        );
    }

    public CompanyDao reserveCompanyId(CompanyDao companyToCreate) {
        synchronized (MarketTransactionLocks.companyAllocation()) {
            Long companyId = companiesRepository.allocateNextId();
            if (companyId == null) return null;
            companyToCreate.setId(companyId.intValue());
            return companyToCreate;
        }
    }

    public boolean saveReservedCompany(CompanyDao companyToCreate) {
        if (companyToCreate == null || companyToCreate.getId() <= 0) return false;
        boolean saved = companiesRepository.save(companyToCreate.toEntity());
        if (saved) Bukkit.getPluginManager().callEvent(new CompanyCreateEvent(companyToCreate));
        return saved;
    }

    public CompanyDao createCompany(CompanyDao companyToCreate) {
        synchronized (MarketTransactionLocks.companyAllocation()) {
            CompanyDao reserved = reserveCompanyId(companyToCreate);
            return reserved != null && saveReservedCompany(reserved) ? reserved : null;
        }
    }

    public double getCompanyCreationTax(long sharesAmount, double sharePrice, int risk) {
        List<Double> extraTaxPerRisk = plugin.getConfig().getDoubleList("Stonks.Taxes.CompanyCreation");
        if (risk <= 0 || risk > extraTaxPerRisk.size() || sharesAmount <= 0 || !Double.isFinite(sharePrice) || sharePrice <= 0) {
            return Double.NaN;
        }
        return sharePrice * sharesAmount * (extraTaxPerRisk.get(risk - 1) + 1);
    }

    public boolean deleteCompany(long companyId) {
        synchronized (MarketTransactionLocks.company(companyId)) {
            CompanyDao deletedCompany = getCompanyById(companyId);
            if (deletedCompany == null || !companiesRepository.delete(companyId)) return false;

            CompanyDeleteEvent deleteEvent = new CompanyDeleteEvent(deletedCompany, !Bukkit.isPrimaryThread());
            try {
                Bukkit.getPluginManager().callEvent(deleteEvent);
            } catch (RuntimeException e) {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "Unable to dispatch CompanyDeleteEvent for deleted company " + companyId, e);
            }
            return true;
        }
    }

    public boolean hasEnoughShares(long companyId, int sharesAmount) {

        if (!companyExists(companyId)) {
            return false;
        }

        // If the company has unlimited stocks it will always be available.
        // Companies with unlimited stocks are marked with "-1" as available stocks.
        return companiesRepository.getById(companyId)
                .map(company -> company.getAvailableShares() >= sharesAmount || company.getAvailableShares() == -1)
                .orElse(false);

    }

    public Double getCompanyInvestmentValue(long companyId, int sharesAmount) {

        if (!companyExists(companyId)) {
            return null;
        }

        return companiesRepository.getById(companyId).map(company -> company.getCurrentSharePrice() * sharesAmount).orElse(null);

    }

    public boolean removeSharesFromCompany(long companyId, int amountOfSharesToRemove) {

        synchronized (MarketTransactionLocks.company(companyId)) {
            CompanyDao companyDao = (CompanyDao) companiesRepository.getById(companyId)
                    .map(companyEntity -> new CompanyDao().fromEntity(companyEntity))
                    .orElse(null);

            if (companyDao == null || amountOfSharesToRemove <= 0) {
                return false;
            }

            if (companyDao.getAvailableShares() == -1 || companyDao.getAvailableShares() - amountOfSharesToRemove < 0) {
                return false;
            }

            companyDao.setAvailableShares(companyDao.getAvailableShares() - amountOfSharesToRemove);
            return companiesRepository.save(companyDao.toEntity());
        }

    }

    public ShareMutationResult removeSharesFromCompanyTyped(long companyId, int quantity) {
        ShareMutationOperation operation = ShareMutationOperation.COMPANY_REMOVE;
        if (quantity <= 0) return ShareMutationResult.rejected(operation, null, companyId, quantity, "quantity must be positive");
        synchronized (MarketTransactionLocks.company(companyId)) {
            CompanyDao company = getCompanyDao(companyId);
            if (company == null || company.getId() != companyId || company.isBankrupt()
                    || quantity <= 0 || company.getTotalShares() < 0
                    || (!Double.isFinite(company.getCurrentSharePrice()) || company.getCurrentSharePrice() < 0)
                    || company.getCurrentSharePrice() == 0.0 && company.getTotalShares() > 0
                    || (company.getAvailableShares() < 0 && company.getAvailableShares() != -1)
                    || (company.getTotalShares() > 0 && company.getAvailableShares() != -1
                    && company.getAvailableShares() > company.getTotalShares())) {
                return ShareMutationResult.rejected(operation, null, companyId, quantity, "company unavailable");
            }
            if (company.getAvailableShares() == -1) {
                return ShareMutationResult.rejected(operation, null, companyId, quantity, "unlimited shares are not removable");
            }
            int before = company.getAvailableShares();
            int after;
            try {
                after = Math.subtractExact(before, quantity);
            } catch (ArithmeticException e) {
                return ShareMutationResult.rejected(operation, null, companyId, quantity, "share arithmetic overflow");
            }
            if (after < 0) return ShareMutationResult.rejected(operation, null, companyId, quantity, "insufficient shares");
            company.setAvailableShares(after);
            try {
                if (!companiesRepository.save(company.toEntity())) {
                    return ShareMutationResult.ambiguous(operation, null, companyId, quantity, (long) before, (long) after,
                            null, null, null, "persistence result unavailable");
                }
                return ShareMutationResult.confirmed(operation, null, companyId, quantity, (long) before, (long) after,
                        null, null, null);
            } catch (RuntimeException e) {
                return ShareMutationResult.ambiguous(operation, null, companyId, quantity, (long) before, (long) after,
                        null, null, null, e.getClass().getSimpleName());
            }
        }
    }

    ShareMutationResult restoreSharesToCompanyTyped(long companyId, ConfirmedCompanyRemovalEvidence evidence) {
        ShareMutationOperation operation = ShareMutationOperation.COMPANY_RESTORE;
        if (evidence == null || evidence.companyId() != companyId) {
            return ShareMutationResult.rejected(operation, null, companyId, 0, "evidence identity mismatch");
        }
        long quantity;
        try {
            quantity = Math.subtractExact(evidence.beforeQuantity(), evidence.afterQuantity());
        } catch (ArithmeticException e) {
            return ShareMutationResult.rejected(operation, null, companyId, 0, "evidence arithmetic overflow");
        }
        if (quantity <= 0 || evidence.afterQuantity() < 0 || evidence.beforeQuantity() < evidence.afterQuantity()) {
            return ShareMutationResult.rejected(operation, null, companyId, quantity, "invalid evidence");
        }
        synchronized (MarketTransactionLocks.company(companyId)) {
            CompanyDao company = getCompanyDao(companyId);
            if (company == null || company.getAvailableShares() != evidence.afterQuantity()) {
                return ShareMutationResult.rejected(operation, null, companyId, quantity, "company state changed");
            }
            int restored;
            try {
                restored = Math.addExact(company.getAvailableShares(), Math.toIntExact(quantity));
            } catch (ArithmeticException e) {
                return ShareMutationResult.rejected(operation, null, companyId, quantity, "share arithmetic overflow");
            }
            if (restored > company.getTotalShares()) return ShareMutationResult.rejected(operation, null, companyId, quantity, "share bound exceeded");
            company.setAvailableShares(restored);
            try {
                if (!companiesRepository.save(company.toEntity())) {
                    return ShareMutationResult.ambiguous(operation, null, companyId, quantity, evidence.afterQuantity(), (long) restored,
                            null, null, null, "persistence result unavailable");
                }
                return ShareMutationResult.confirmed(operation, null, companyId, quantity, evidence.afterQuantity(), (long) restored,
                        null, null, null);
            } catch (RuntimeException e) {
                return ShareMutationResult.ambiguous(operation, null, companyId, quantity, evidence.afterQuantity(), (long) restored,
                        null, null, null, e.getClass().getSimpleName());
            }
        }
    }

    private CompanyDao getCompanyDao(long companyId) {
        return (CompanyDao) companiesRepository.getById(companyId)
                .map(entity -> new CompanyDao().fromEntity(entity)).orElse(null);
    }

    public ShareMutationResult addSharesToCompanyTyped(long companyId, int quantity) {
        ShareMutationOperation operation = ShareMutationOperation.COMPANY_ADD;
        if (quantity <= 0) return ShareMutationResult.rejected(operation, null, companyId, quantity, "quantity must be positive");
        synchronized (MarketTransactionLocks.company(companyId)) {
            CompanyDao company = getCompanyDao(companyId);
            if (company == null || company.getId() != companyId || company.isBankrupt()
                    || quantity <= 0 || company.getTotalShares() < 0
                    || (!Double.isFinite(company.getCurrentSharePrice()) || company.getCurrentSharePrice() < 0)
                    || company.getCurrentSharePrice() == 0.0 && company.getTotalShares() > 0
                    || (company.getAvailableShares() < 0 && company.getAvailableShares() != -1)
                    || (company.getTotalShares() > 0 && company.getAvailableShares() != -1
                    && company.getAvailableShares() > company.getTotalShares())) {
                return ShareMutationResult.rejected(operation, null, companyId, quantity, "company unavailable");
            }
            if (company.getAvailableShares() == -1) {
                return ShareMutationResult.rejected(operation, null, companyId, quantity, "unlimited shares are not returnable");
            }
            int before = company.getAvailableShares();
            int after;
            try {
                after = Math.addExact(before, quantity);
            } catch (ArithmeticException e) {
                return ShareMutationResult.rejected(operation, null, companyId, quantity, "share arithmetic overflow");
            }
            if (after > company.getTotalShares()) {
                return ShareMutationResult.rejected(operation, null, companyId, quantity, "share bound exceeded");
            }
            company.setAvailableShares(after);
            try {
                if (!companiesRepository.save(company.toEntity())) {
                    return ShareMutationResult.ambiguous(operation, null, companyId, quantity, (long) before, (long) after,
                            null, null, null, "persistence result unavailable");
                }
                return ShareMutationResult.confirmed(operation, null, companyId, quantity, (long) before, (long) after,
                        null, null, null);
            } catch (RuntimeException e) {
                return ShareMutationResult.ambiguous(operation, null, companyId, quantity, (long) before, (long) after,
                        null, null, null, e.getClass().getSimpleName());
            }
        }
    }

    ShareMutationResult removeConfirmedCompanyAddTyped(long companyId, ConfirmedCompanyAddEvidence evidence) {
        ShareMutationOperation operation = ShareMutationOperation.COMPANY_ADD_RESTORE;
        if (evidence == null || evidence.companyId() != companyId) {
            return ShareMutationResult.rejected(operation, null, companyId, 0, "evidence identity mismatch");
        }
        long quantity;
        try {
            quantity = Math.subtractExact(evidence.afterQuantity(), evidence.beforeQuantity());
        } catch (ArithmeticException e) {
            return ShareMutationResult.rejected(operation, null, companyId, 0, "evidence arithmetic overflow");
        }
        if (quantity <= 0 || evidence.beforeQuantity() < 0 || evidence.afterQuantity() < evidence.beforeQuantity()) {
            return ShareMutationResult.rejected(operation, null, companyId, quantity, "invalid evidence");
        }
        synchronized (MarketTransactionLocks.company(companyId)) {
            CompanyDao company = getCompanyDao(companyId);
            if (company == null || company.getAvailableShares() != evidence.afterQuantity()) {
                return ShareMutationResult.rejected(operation, null, companyId, quantity, "company state changed");
            }
            int restored;
            try {
                restored = Math.subtractExact(company.getAvailableShares(), Math.toIntExact(quantity));
            } catch (ArithmeticException e) {
                return ShareMutationResult.rejected(operation, null, companyId, quantity, "share arithmetic overflow");
            }
            if (restored < 0) return ShareMutationResult.rejected(operation, null, companyId, quantity, "insufficient shares");
            company.setAvailableShares(restored);
            try {
                if (!companiesRepository.save(company.toEntity())) {
                    return ShareMutationResult.ambiguous(operation, null, companyId, quantity, evidence.afterQuantity(), (long) restored,
                            null, null, null, "persistence result unavailable");
                }
                return ShareMutationResult.confirmed(operation, null, companyId, quantity, evidence.afterQuantity(), (long) restored,
                        null, null, null);
            } catch (RuntimeException e) {
                return ShareMutationResult.ambiguous(operation, null, companyId, quantity, evidence.afterQuantity(), (long) restored,
                        null, null, null, e.getClass().getSimpleName());
            }
        }
    }

    public boolean addSharesToCompany(long companyId, int amountOfSharesToAdd) {

        synchronized (MarketTransactionLocks.company(companyId)) {
            CompanyDao companyDao = (CompanyDao) companiesRepository.getById(companyId)
                    .map(companyEntity -> new CompanyDao().fromEntity(companyEntity))
                    .orElse(null);

            if (companyDao == null || amountOfSharesToAdd <= 0) {
                return false;
            }

            if (companyDao.getAvailableShares() == -1 || companyDao.getTotalShares() < companyDao.getAvailableShares() + amountOfSharesToAdd) {
                return false;
            }

            companyDao.setAvailableShares(companyDao.getAvailableShares() + amountOfSharesToAdd);
            return companiesRepository.save(companyDao.toEntity());
        }

    }

    public List<CompanyDao> getCompaniesByIdInterval(int startingId, int endingId) {

        List<Long> allowedIds = companiesRepository.getAllIds().stream()
                .filter(id -> id >= startingId && id <= endingId)
                .collect(Collectors.toList());

        return allowedIds.stream()
                .map(companiesRepository::getById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(companyEntity -> (CompanyDao) new CompanyDao().fromEntity(companyEntity))
                .collect(Collectors.toUnmodifiableList());

    }

    public boolean companyExists(long companyId) {
        return companiesRepository.exists(companyId) && companiesRepository.getById(companyId).isPresent();
    }

    public boolean updateCompanySharesValue(long companyId, double newSharesValue, double variation) {
        synchronized (MarketTransactionLocks.company(companyId)) {
            if (!Double.isFinite(newSharesValue) || newSharesValue < 0 || !Double.isFinite(variation)) {
                return false;
            }
            CompanyDao companyDao = (CompanyDao) companiesRepository.getById(companyId)
                    .map(companyEntity -> new CompanyDao().fromEntity(companyEntity))
                    .orElse(null);

            if (companyDao == null) {
                return false;
            }

            double oldShareValue = companyDao.getCurrentSharePrice();
            companyDao.setCurrentSharePrice(newSharesValue);
            boolean saved = companiesRepository.save(companyDao.toEntity());

            if (saved) {
                CompanyStockUpdateEvent stockUpdateEvent = new CompanyStockUpdateEvent(
                        companyDao, oldShareValue, newSharesValue, variation);
                Bukkit.getPluginManager().callEvent(stockUpdateEvent);
            }
            return saved;
        }
    }

    public boolean updateCompanyHistoric(long companyId, QuoteDao newQuote) {
        synchronized (MarketTransactionLocks.company(companyId)) {
            CompanyDao companyDao = (CompanyDao) companiesRepository.getById(companyId)
                    .map(companyEntity -> new CompanyDao().fromEntity(companyEntity))
                    .orElse(null);

            if (companyDao == null) {
                return false;
            }

            companyDao.getHistoric().push(newQuote);
            return companiesRepository.save(companyDao.toEntity());
        }
    }

    public boolean persistSchedulerSnapshot(CompanyDao snapshot) {
        if (snapshot == null || snapshot.getId() < 0
                || !Double.isFinite(snapshot.getCurrentSharePrice())
                || snapshot.getCurrentSharePrice() < 0
                || snapshot.getHistoric() == null) {
            return false;
        }
        synchronized (MarketTransactionLocks.company(snapshot.getId())) {
            return companiesRepository.save(snapshot.toEntity());
        }
    }

    public boolean markCompanyBankrupt(long companyId) {
        synchronized (MarketTransactionLocks.company(companyId)) {
            CompanyDao companyDao = (CompanyDao) companiesRepository.getById(companyId)
                    .map(companyEntity -> new CompanyDao().fromEntity(companyEntity))
                    .orElse(null);

            if (companyDao == null) {
                return false;
            }

            companyDao.setBankrupt(true);
            companyDao.setCurrentSharePrice(0.0);
            return companiesRepository.save(companyDao.toEntity());
        }
    }

    public double getMarketImpactPerShare() {
        return marketSetting("Stonks.MarketImpactPerShare", 0.00003, 0.01, true);
    }

    public double getTradingTax() {
        return marketSetting("Stonks.Taxes.Trading", 0.01, 0.5, false);
    }

    public double getTradingMinimum() {
        return marketSetting("Stonks.Taxes.TradingMinimum", 1.0, Double.POSITIVE_INFINITY, false);
    }

    private double marketSetting(String key, double defaultValue, double maximum, boolean maximumInclusive) {
        double value = plugin.getConfig().getDouble(key, defaultValue);
        if (!Double.isFinite(value) || value < 0 || (maximumInclusive ? value > maximum : value >= maximum)) {
            synchronized (warnedMarketSettings) {
                if (warnedMarketSettings.add(key)) {
                    plugin.getLogger().warning("Invalid " + key + "; using default " + defaultValue);
                }
            }
            return defaultValue;
        }
        return value;
    }

    public boolean applyMarketImpact(UUID playerUuid, long companyId, int shares, boolean isPurchase) {
        synchronized (MarketTransactionLocks.company(companyId)) {
            CompanyDao company = getCompanyById(companyId);
            if (company == null || company.isBankrupt()) return false;

            double currentPrice = company.getCurrentSharePrice();
        double impactPerShare = getMarketImpactPerShare();
        if (!Double.isFinite(currentPrice) || currentPrice <= 0 || shares <= 0) {
            return false;
        }

        double newPrice = isPurchase
                ? MarketPricing.buyPriceAfter(currentPrice, shares, impactPerShare)
                : MarketPricing.sellPriceAfter(currentPrice, shares, impactPerShare);
        if (!Double.isFinite(newPrice) || newPrice <= 0) {
            return false;
        }
        double variation = (newPrice - currentPrice) / currentPrice;

        // 2. WHALE ALERT LOGIC
        // Threshold can be moved to config, using 500 as default
        int whaleThreshold = plugin.getConfig().getInt("Stonks.WhaleThreshold", 500);
        if (shares >= whaleThreshold) {
            String pName = Bukkit.getOfflinePlayer(playerUuid).getName();
            if (pName == null) pName = playerUuid.toString();
            String action = isPurchase ? "§aBOUGHT" : "§cDUMPED";

            Bukkit.broadcastMessage("§6§l[WHALE ALERT] §f" + pName + " just " + action +
                    " §e" + shares + " shares §fof §b" + company.getName() + "!");
        }

        // 3. Persist the change
        boolean saved = updateCompanySharesValue(companyId, newPrice, variation);

        if (saved) {
            plugin.getSignsService().updateBukkitSignsByCompany(companyId);
        } else {
            plugin.getLogger().severe("CRITICAL: Failed to save market impact for company " + companyId);
        }
        return saved;
        }
    }

    @Override
    public Repository<?, ?> getRepository() {
        return companiesRepository;
    }

}
