package com.reallive.paymentservice.wallet.entity;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UserWalletEntityTest {

    @Test
    void newWalletDefaultsToZeroBalance() {
        assertEquals(0L, UserWalletEntity.builder().userId(UUID.randomUUID()).build().getBalance());
    }

    @Test
    void entityIsEqualToItselfEvenBeforePersistence() {
        UserWalletEntity wallet = new UserWalletEntity();
        assertEquals(wallet, wallet);
    }

    @Test
    void differentTransientWalletsAreNotEqual() {
        assertNotEquals(new UserWalletEntity(), new UserWalletEntity());
    }

    @Test
    void entityDoesNotEqualNullOrAnotherType() {
        UserWalletEntity wallet = new UserWalletEntity();
        assertFalse(wallet.equals(null));
        assertFalse(wallet.equals("wallet"));
    }

    @Test
    void persistedEqualityUsesWalletIdAndIgnoresBalance() {
        UUID id = UUID.randomUUID();
        UserWalletEntity first = UserWalletEntity.builder().id(id).balance(0L).build();
        UserWalletEntity second = UserWalletEntity.builder().id(id).balance(999L).build();

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, UserWalletEntity.builder().id(UUID.randomUUID()).build());
        assertNotEquals(first, new UserWalletEntity());
    }

    @Test
    void hashCodeDoesNotChangeWhenIdIsAssigned() {
        UserWalletEntity wallet = new UserWalletEntity();
        int hash = wallet.hashCode();
        wallet.setId(UUID.randomUUID());
        assertEquals(hash, wallet.hashCode());
    }
}
