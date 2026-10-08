package com.reallive.userservice.user.mapper;

import com.reallive.userservice.user.dto.response.UserProfileResponse;
import com.reallive.userservice.user.entity.ProfileEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR,
        builder = @Builder(disableBuilder = true))
public interface ProfileMapper {

    @Mapping(target = "userId", source = "user.id")
    @Mapping(target = "email", source = "user.email")
    @Mapping(target = "createdAt", source = "user.createdAt")
    UserProfileResponse toResponse(ProfileEntity profile);
}
