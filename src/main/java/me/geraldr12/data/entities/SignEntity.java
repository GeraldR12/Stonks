package me.geraldr12.data.entities;

import lombok.Data;

@Data
public class SignEntity implements DataEntity {

    private int id;
    private long companyId;
    private LocationEntity location;

}
