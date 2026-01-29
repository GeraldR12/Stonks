package me.geraldr12.data.dao;

import me.geraldr12.data.entities.QuoteEntity;
import lombok.*;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode
@ToString
public class QuoteDao implements Dao<QuoteEntity> {

    private double variation;
    private double price;
    private long timestamp;

    @Override
    public QuoteEntity toEntity() {

        QuoteEntity entity = new QuoteEntity();

        entity.setVariation(variation);
        entity.setPrice(price);
        entity.setTimestamp(timestamp);

        return entity;

    }

    @Override
    public Dao<QuoteEntity> fromEntity(QuoteEntity entity) {
        return new QuoteDao(
                entity.getVariation(),
                entity.getPrice(),
                entity.getTimestamp()
        );
    }

}
