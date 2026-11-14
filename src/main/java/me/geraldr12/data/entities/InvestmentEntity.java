package me.geraldr12.data.entities;

import lombok.Data;

@Data
public class InvestmentEntity implements DataEntity {

    private long companyId;
    private int sharesAmount;
    private Double averageBuyPrice;

}