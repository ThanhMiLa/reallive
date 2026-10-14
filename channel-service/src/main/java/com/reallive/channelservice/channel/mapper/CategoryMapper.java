package com.reallive.channelservice.channel.mapper;

import com.reallive.channelservice.channel.dto.response.CategoryResponse;
import com.reallive.channelservice.channel.entity.CategoryEntity;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface CategoryMapper {

    CategoryResponse toResponse(CategoryEntity category);

    List<CategoryResponse> toResponseList(List<CategoryEntity> categories);
}
