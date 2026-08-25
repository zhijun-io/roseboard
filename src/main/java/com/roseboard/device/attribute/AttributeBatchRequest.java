package com.roseboard.device.attribute;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import tools.jackson.databind.JsonNode;

public record AttributeBatchRequest(
        @NotNull AttributeBatchMode mode,
        @NotEmpty List<@Valid Item> items) {

    public record Item(
            @NotNull AttributeScope scope,
            @NotNull @NotBlank String key,
            @NotNull JsonNode value,
            Long expectedVersion) {
    }
}
