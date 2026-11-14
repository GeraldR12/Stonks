package me.geraldr12.data.entities;

import lombok.Data;

@Data
public class LocationEntity implements DataEntity {

    private String world;
    private double x;
    private double y;
    private double z;

}
