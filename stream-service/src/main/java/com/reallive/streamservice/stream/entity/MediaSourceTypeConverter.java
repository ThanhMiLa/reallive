package com.reallive.streamservice.stream.entity;

import com.reallive.streamservice.stream.entity.enums.MediaSourceType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class MediaSourceTypeConverter implements AttributeConverter<MediaSourceType, String> {

    @Override
    public String convertToDatabaseColumn(MediaSourceType attribute) {
        return attribute == null ? null : attribute.getValue();
    }

    @Override
    public MediaSourceType convertToEntityAttribute(String dbData) {
        return MediaSourceType.fromValue(dbData);
    }
}
