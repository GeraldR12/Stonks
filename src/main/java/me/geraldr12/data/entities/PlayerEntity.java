package me.geraldr12.data.entities;

import me.geraldr12.enums.NotificationType;
import lombok.Data;

import java.util.List;
import java.util.Set;

@Data
public class PlayerEntity implements DataEntity {

    private String uniqueId;
    private String name;
    private List<InvestmentEntity> investments;
    private Set<NotificationType> blockedNotifications;

}
