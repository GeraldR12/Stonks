package me.geraldr12.data.dao;

import me.geraldr12.data.entities.CompanyEntity;
import me.geraldr12.utils.SizedStack;
import lombok.*;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.util.stream.Collectors;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode
@ToString
public class CompanyDao implements Dao<CompanyEntity> {

    @Setter
    private int id;
    private String name;
    private String description;
    @Nullable
    private Material icon;
    private double initialSharePrice;
    @Setter
    private double currentSharePrice;
    private int risk;
    private int totalShares;
    @Setter
    private int availableShares;
    private SizedStack<QuoteDao> historic;
    private String ownerUuid;

    @Setter
    private boolean bankrupt;
    @Setter
    private Long bankruptcyTime;
    @Setter
    private Double bankruptcyPreviousPrice;

    @Override
    public CompanyEntity toEntity() {
        CompanyEntity entity = new CompanyEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setDescription(description);
        entity.setIcon(icon);
        entity.setInitialSharePrice(initialSharePrice);
        entity.setCurrentSharePrice(currentSharePrice);
        entity.setRisk(risk);
        entity.setTotalShares(totalShares);
        entity.setAvailableShares(availableShares);
        entity.setHistoric(historic.stream().map(Dao::toEntity).collect(Collectors.toList()));
        entity.setOwnerUuid(ownerUuid);
        entity.setBankrupt(bankrupt);
        entity.setBankruptcyTime(bankruptcyTime);
        entity.setBankruptcyPreviousPrice(bankruptcyPreviousPrice);
        return entity;
    }

    @Override
    public Dao<CompanyEntity> fromEntity(CompanyEntity entity) {
        // USE THE BUILDER INSTEAD OF THE CONSTRUCTOR
        return CompanyDao.builder()
                .id(entity.getId())
                .name(entity.getName())
                .description(entity.getDescription())
                .icon(entity.getIcon())
                .initialSharePrice(entity.getInitialSharePrice())
                .currentSharePrice(entity.getCurrentSharePrice())
                .risk(entity.getRisk())
                .totalShares(entity.getTotalShares())
                .availableShares(entity.getAvailableShares())
                .historic(new SizedStack<QuoteDao>(500).fromList(
                        entity.getHistoric().stream()
                                .map(quote -> (QuoteDao) new QuoteDao().fromEntity(quote))
                                // Oldest first. Versions up to 2.10.2 could save the list out of order.
                                .sorted(java.util.Comparator.comparingLong(QuoteDao::getTimestamp))
                                .collect(Collectors.toList())
                ))
                .ownerUuid(entity.getOwnerUuid())
                .bankrupt(entity.isBankrupt())
                .bankruptcyTime(entity.getBankruptcyTime())
                .bankruptcyPreviousPrice(entity.getBankruptcyPreviousPrice())
                .build();
    }

    public boolean isBankrupt() {
        return bankrupt || currentSharePrice == 0.0;
    }

    public boolean isTrading() {
        return !isBankrupt() && Double.isFinite(currentSharePrice) && currentSharePrice > 0;
    }

    public Long getBankruptcyTime() {
        if (bankruptcyTime != null || !isBankrupt() || historic == null) return bankruptcyTime;
        return historic.stream()
                .filter(quote -> quote.getPrice() == 0.0 && quote.getTimestamp() > 0)
                .map(QuoteDao::getTimestamp)
                .reduce((previous, latest) -> latest).orElse(null);
    }

    public Double getBankruptcyPreviousPrice() {
        Long closedAt = getBankruptcyTime();
        if (bankruptcyPreviousPrice != null || closedAt == null || historic == null) {
            return bankruptcyPreviousPrice;
        }
        return historic.stream()
                .filter(quote -> quote.getTimestamp() < closedAt && Double.isFinite(quote.getPrice()) && quote.getPrice() > 0)
                .map(QuoteDao::getPrice).reduce((previous, latest) -> latest).orElse(null);
    }
}
