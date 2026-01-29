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
    private boolean bankrupt; // The new field

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
        entity.setBankrupt(bankrupt); // Ensure this exists in CompanyEntity!
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
                                .collect(Collectors.toList())
                ))
                .ownerUuid(entity.getOwnerUuid())
                .bankrupt(entity.isBankrupt()) // This maps the new field
                .build();
    }

    // This helper checks BOTH the explicit boolean and the price
    public boolean isBankrupt() {
        return bankrupt || this.getCurrentSharePrice() <= 0.0;
    }
}