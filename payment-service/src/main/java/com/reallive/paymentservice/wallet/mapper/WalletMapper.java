package com.reallive.paymentservice.wallet.mapper;

import com.reallive.paymentservice.wallet.dto.response.WalletCreationResponse;
import com.reallive.paymentservice.wallet.entity.UserWalletEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
        builder = @Builder(disableBuilder = true),
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface WalletMapper {
    WalletCreationResponse toWalletCreationResponse(UserWalletEntity userWalletEntity);
}
