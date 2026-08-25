package com.roseboard.infrastructure.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.media.StringSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
    @Bean
    OpenAPI roseboardOpenApi() {
        ObjectSchema loginRequest = objectSchema("username", "password");
        ObjectSchema loginResponse = objectSchema("token", "refreshToken");
        ObjectSchema refreshRequest = objectSchema("refreshToken");

        return new OpenAPI()
                .components(new Components()
                        .addSchemas("LoginRequest", loginRequest)
                        .addSchemas("LoginResponse", loginResponse)
                        .addSchemas("RefreshTokenRequest", refreshRequest))
                .paths(new Paths()
                        .addPathItem("/api/login", post("Login", "LoginRequest", "LoginResponse"))
                        .addPathItem("/api/login/public", post("PublicLogin", "LoginRequest", "LoginResponse"))
                        .addPathItem("/api/token", post("RefreshToken", "RefreshTokenRequest", "LoginResponse")));
    }

    private ObjectSchema objectSchema(String... properties) {
        ObjectSchema schema = new ObjectSchema();
        for (String property : properties) schema.addProperties(property, new StringSchema());
        return schema;
    }

    private PathItem post(String operationId, String requestSchema, String responseSchema) {
        Content requestContent = new Content().addMediaType("application/json",
                new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + requestSchema)));
        Content responseContent = new Content().addMediaType("application/json",
                new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + responseSchema)));
        Operation operation = new Operation().operationId(operationId)
                .requestBody(new io.swagger.v3.oas.models.parameters.RequestBody().required(true).content(requestContent))
                .responses(new ApiResponses().addApiResponse("200", new ApiResponse().description("OK").content(responseContent))
                        .addApiResponse("401", new ApiResponse().description("Authentication failed")));
        return new PathItem().post(operation);
    }
}
