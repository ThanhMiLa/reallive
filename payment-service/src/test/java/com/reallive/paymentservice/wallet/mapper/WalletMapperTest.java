package com.reallive.paymentservice.wallet.mapper;

import com.reallive.paymentservice.wallet.entity.UserWalletEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WalletMapperTest {

    private final WalletMapper mapper = Mappers.getMapper(WalletMapper.class);

    @Test
    void mapsIdentityAndExistingNonZeroBalance() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        var response = mapper.toWalletCreationResponse(UserWalletEntity.builder()
                .id(id).userId(userId).balance(875L).build());

        assertEquals(id, response.getId());
        assertEquals(userId, response.getUserId());
        assertEquals(875L, response.getBalance());
    }

    @Test
    void nullEntityMapsToNull() {
        assertNull(mapper.toWalletCreationResponse(null));
    }
}
