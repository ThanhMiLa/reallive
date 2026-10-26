package com.reallive.streamservice.stream.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.AssertTrue;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import java.util.UUID;

@Getter
@JsonAutoDetect(fieldVisibility = Visibility.NONE, getterVisibility = Visibility.NONE,
        isGetterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
@FieldDefaults(level = AccessLevel.PRIVATE)
public class UpdateStreamSessionRequest {
    String title;
    UUID categoryId;
    boolean titleProvided;
    boolean categoryProvided;

    @JsonSetter
    public void setTitle(String value) {
        titleProvided = true;
        title = value == null ? null : value.trim();
    }

    @JsonSetter
    public void setCategoryId(UUID value) {
        categoryProvided = true;
        categoryId = value;
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unsupported stream update field");
    }

    @AssertTrue(message = "Provide a nonempty title or categoryId; explicit null is not allowed")
    public boolean isValidPatch() {
        return (titleProvided || categoryProvided)
                && (!titleProvided || (title != null && !title.isBlank() && title.length() <= 255))
                && (!categoryProvided || categoryId != null);
    }
}
