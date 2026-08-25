package com.roseboard.ota;

import com.roseboard.common.PageData;

import java.util.UUID;

public interface OtaPackageService {
    OtaPackageInfo create(OtaPackageCreateRequest request);

    OtaPackageInfo findById(UUID tenantId, UUID packageId);

    PageData<OtaPackageInfo> findPage(UUID tenantId, long pageSize, long page, String textSearch);

    PageData<OtaPackageInfo> findPageByProfileAndKind(UUID tenantId, UUID deviceProfileId, String kind,
                                                     long pageSize, long page, String textSearch);
}
