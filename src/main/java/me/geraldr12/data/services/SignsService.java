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
import org.bukkit.block.Sign;

import javax.inject.Inject;
import java.util.List;
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

        SignDao signDao = SignDao.builder()
                .id(repository.getNextId().intValue())
                .companyId(companyId)
                .location(LocationDao.fromBukkitLocation(signLocation))
                .build();

        repository.save(signDao.toEntity());

        return signDao;

    }

    public void deleteSign(int signId) {
        repository.getById(signId).ifPresent(signEntity -> repository.delete(signEntity.getId()));
    }

    public SignDao getSignByLocation(Location location) {

        return getAllSigns().stream()
                .filter(signDao -> signDao.getLocation().toBukkitLocation().equals(location))
                .findFirst()
                .orElse(null);

    }

    public Sign getBukkitSignFromId(int id) {

        if (!repository.exists(id)) return null;

        Optional<SignEntity> signEntityOptional = repository.getById(id);

        if (signEntityOptional.isEmpty()) return null;

        SignDao signDao = (SignDao) new SignDao().fromEntity(signEntityOptional.get());
        Location signLocation = signDao.getLocation().toBukkitLocation();

        if (signLocation.getBlock().getState() instanceof Sign) {
            return (Sign) signLocation.getBlock().getState();
        }

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
