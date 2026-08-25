package com.roseboard.ota;

import com.roseboard.user.UserAuthority;
import com.roseboard.common.PageData;
import com.roseboard.setting.security.DataScopeAuthorizer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OtaPackageControllerTest {
    private final OtaPackageServiceImpl packageService = mock(OtaPackageServiceImpl.class);
    private final DataScopeAuthorizer dataScopeService = mock(DataScopeAuthorizer.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new OtaPackageController(packageService, dataScopeService))
            .setControllerAdvice(new OtaExceptionHandler())
            .build();

    @Test
    void createUploadListAndDeleteFollowTbPaths() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID packageId = UUID.randomUUID();
        when(dataScopeService.requireTenantId(any())).thenReturn(tenantId);
        when(packageService.create(any())).thenReturn(info(packageId, tenantId, profileId));
        when(packageService.uploadAndPublish(eq(tenantId), eq(packageId), any(), isNull(), eq("SHA256")))
                .thenReturn(info(packageId, tenantId, profileId));
        when(packageService.findPage(eq(tenantId), eq(10L), eq(0L), isNull()))
                .thenReturn(new PageData<>(List.of(info(packageId, tenantId, profileId)), 10, 0, 1));

        mvc.perform(post("/api/ota-packages")
                        .principal(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "deviceProfileId":"%s",
                                  "type":"FIRMWARE",
                                  "title":"fw",
                                  "version":"1.0.0",
                                  "usesUrl":false
                                }
                                """.formatted(profileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(packageId.toString()))
                .andExpect(jsonPath("$.kind").value("firmware"));

        mvc.perform(multipart(HttpMethod.PUT, "/api/ota-packages/{id}/content", packageId)
                        .file(new MockMultipartFile("file", "fw.bin", "application/octet-stream", new byte[]{1, 2}))
                        .param("checksumAlgorithm", "SHA256")
                        .principal(auth()))
                .andExpect(status().isOk());

        mvc.perform(get("/api/ota-packages").param("pageSize", "10").param("page", "0").principal(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(packageId.toString()));

        mvc.perform(delete("/api/ota-packages/{id}", packageId).principal(auth()))
                .andExpect(status().isOk());
        verify(packageService).delete(tenantId, packageId);
    }

    private static UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken("user", "n/a",
                List.of(new SimpleGrantedAuthority(UserAuthority.TENANT_ADMIN.name())));
    }

    private static OtaPackageInfo info(UUID id, UUID tenantId, UUID profileId) {
        return new OtaPackageInfo(id, tenantId, profileId, "firmware", "fw", "1.0.0", null,
                "fw.bin", "application/octet-stream", OtaArtifactSource.BINARY, null,
                2L, "SHA256", "ab", OtaPackageStatus.PUBLISHED, 1L, 1L);
    }
}
