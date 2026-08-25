package com.roseboard.ota;

import com.roseboard.audit.AuditActions;
import com.roseboard.common.PageData;
import com.roseboard.infrastructure.audit.Audited;
import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.setting.security.DataScopeAuthorizer;
import com.roseboard.infrastructure.security.api.Operation;
import com.roseboard.infrastructure.security.api.RequirePermission;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

/**
 * OTA 包管理接口：提供 OTA 包查询、创建、内容上传和删除。
 */
@RestController
@RequestMapping("/api/ota-packages")
public class OtaPackageController {
    private final OtaPackageServiceImpl packageService;
    private final DataScopeAuthorizer dataScopeService;

    public OtaPackageController(OtaPackageServiceImpl packageService, DataScopeAuthorizer dataScopeService) {
        this.packageService = packageService;
        this.dataScopeService = dataScopeService;
    }

    /**
     * 查询单个资源。
     */
    @GetMapping("/{otaPackageId}")
    @RequirePermission(resource = EntityType.OTA_PACKAGE, operation = Operation.READ)
    public OtaPackageInfo get(@PathVariable UUID otaPackageId, Authentication authentication) {
        return packageService.findById(dataScopeService.requireTenantId(authentication), otaPackageId);
    }

    /**
     * 下载 OTA 包内容。
     */
    @GetMapping("/{otaPackageId}/content")
    @RequirePermission(resource = EntityType.OTA_PACKAGE, operation = Operation.READ)
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID otaPackageId,
                                                        Authentication authentication) {
        UUID tenantId = dataScopeService.requireTenantId(authentication);
        OtaPackageInfo info = packageService.findById(tenantId, otaPackageId);
        if (info.artifactSource() == OtaArtifactSource.EXTERNAL_URL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL package is not downloadable");
        }
        String fileName = info.fileName() == null || info.fileName().isBlank() ? "package.bin" : info.fileName();
        String contentType = info.contentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE : info.contentType();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=" + fileName)
                .header("x-filename", fileName)
                .contentType(MediaType.parseMediaType(contentType))
                .body(new InputStreamResource(packageService.download(tenantId, otaPackageId)));
    }

    /**
     * 分页查询资源。
     */
    @GetMapping
    @RequirePermission(resource = EntityType.OTA_PACKAGE, operation = Operation.READ)
    public PageData<OtaPackageInfo> list(@RequestParam long pageSize, @RequestParam long page,
                                         @RequestParam(required = false) String textSearch,
                                         Authentication authentication) {
        return packageService.findPage(dataScopeService.requireTenantId(authentication), pageSize, page, textSearch);
    }

    /**
     * 按设备画像和类型查询 OTA 包。
     */
    @GetMapping(params = {"deviceProfileId", "type"})
    @RequirePermission(resource = EntityType.OTA_PACKAGE, operation = Operation.READ)
    public PageData<OtaPackageInfo> listByProfile(@RequestParam UUID deviceProfileId,
                                                  @RequestParam String type,
                                                  @RequestParam long pageSize, @RequestParam long page,
                                                  @RequestParam(required = false) String textSearch,
                                                  Authentication authentication) {
        return packageService.findPageByProfileAndKind(
                dataScopeService.requireTenantId(authentication), deviceProfileId, type.toLowerCase(Locale.ROOT),
                pageSize, page, textSearch);
    }

    /**
     * 创建或更新资源。
     */
    @PostMapping
    @RequirePermission(resource = EntityType.OTA_PACKAGE, operation = Operation.WRITE)
    @Audited(action = AuditActions.OTA_PACKAGE_CREATED, entityType = EntityType.OTA_PACKAGE,
            entityId = "#result.id()", entityName = "#result.title()")
    public OtaPackageInfo save(@RequestBody OtaPackageSaveRequest request, Authentication authentication) {
        UUID tenantId = dataScopeService.requireTenantId(authentication);
        if (request.id() != null) {
            return packageService.updateMutableFields(
                    tenantId, request.id(), request.title(), request.version(), request.tag());
        }
        boolean usesUrl = request.resolveUsesUrl();
        return packageService.create(new OtaPackageCreateRequest(
                tenantId, request.deviceProfileId(), request.resolvedKind(),
                request.title(), request.version(), request.tag(),
                usesUrl ? OtaArtifactSource.EXTERNAL_URL : OtaArtifactSource.BINARY,
                usesUrl ? request.url() : null,
                request.fileName(), request.contentType()));
    }

    /**
     * 上传 OTA 包内容并发布。
     */
    @PutMapping(value = "/{otaPackageId}/content", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequirePermission(resource = EntityType.OTA_PACKAGE, operation = Operation.WRITE)
    @Audited(action = AuditActions.OTA_PACKAGE_PUBLISHED, entityType = EntityType.OTA_PACKAGE,
            entityId = "#otaPackageId")
    public OtaPackageInfo upload(@PathVariable UUID otaPackageId,
                                 @RequestParam(required = false) String checksum,
                                 @RequestParam String checksumAlgorithm,
                                 @RequestPart MultipartFile file,
                                 Authentication authentication) throws IOException {
        return packageService.uploadAndPublish(
                dataScopeService.requireTenantId(authentication), otaPackageId, file.getInputStream(),
                checksum, checksumAlgorithm);
    }

    /**
     * 删除资源。
     */
    @DeleteMapping("/{otaPackageId}")
    @RequirePermission(resource = EntityType.OTA_PACKAGE, operation = Operation.DELETE)
    @Audited(action = AuditActions.OTA_PACKAGE_DELETED, entityType = EntityType.OTA_PACKAGE,
            entityId = "#otaPackageId")
    public void delete(@PathVariable UUID otaPackageId, Authentication authentication) {
        packageService.delete(dataScopeService.requireTenantId(authentication), otaPackageId);
    }
}
