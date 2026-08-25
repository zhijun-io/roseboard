package com.roseboard.tenant;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.UUID;

/**
 * Explicit deletion operations for tenant-owned data.
 *
 * <p>These statements intentionally live in the persistence layer. Services
 * decide the deletion order and transaction boundary.</p>
 */
@Mapper
public interface TenantDataCleanupMapper {

    @Delete("delete from notification where recipient_id = #{userId}")
    int deleteNotificationsByUser(@Param("userId") UUID userId);

    @Delete("delete from api_key where user_id = #{userId}")
    int deleteApiKeysByUser(@Param("userId") UUID userId);

    @Delete("delete from user_setting where user_id = #{userId}")
    int deleteSettingsByUser(@Param("userId") UUID userId);

    @Delete("delete from user_credential where user_id = #{userId}")
    int deleteCredentialsByUser(@Param("userId") UUID userId);

    @Update("update device set customer_id = null where customer_id = #{customerId}")
    int unassignDevicesFromCustomer(@Param("customerId") UUID customerId);

    @Delete("delete from notification where scope_id = #{tenantId}")
    int deleteNotificationsByTenant(@Param("tenantId") UUID tenantId);

    @Delete("delete from notification_template where tenant_id = #{tenantId}")
    int deleteNotificationTemplatesByTenant(@Param("tenantId") UUID tenantId);

    @Delete("delete from notification_channel_config where tenant_id = #{tenantId}")
    int deleteNotificationChannelsByTenant(@Param("tenantId") UUID tenantId);

    @Delete("delete from notification_target where tenant_id = #{tenantId}")
    int deleteNotificationTargetsByTenant(@Param("tenantId") UUID tenantId);

    @Delete("delete from tenant_usage_counter where tenant_id = #{tenantId}")
    int deleteUsageCountersByTenant(@Param("tenantId") UUID tenantId);

    @Delete("delete from admin_setting where tenant_id = #{tenantId}")
    int deleteAdminSettingsByTenant(@Param("tenantId") UUID tenantId);

    @Delete("delete from oauth2_client where tenant_id = #{tenantId}")
    int deleteOAuth2ClientsByTenant(@Param("tenantId") UUID tenantId);

    @Delete("delete from domain where tenant_id = #{tenantId}")
    int deleteDomainsByTenant(@Param("tenantId") UUID tenantId);

    @Delete("delete from queue_stats where tenant_id = #{tenantId}")
    int deleteQueueStatsByTenant(@Param("tenantId") UUID tenantId);

    @Delete("delete from queue where tenant_id = #{tenantId}")
    int deleteQueuesByTenant(@Param("tenantId") UUID tenantId);

    @Delete("delete from device_rpc where tenant_id = #{tenantId}")
    int deleteDeviceRpcsByTenant(@Param("tenantId") UUID tenantId);

    @Delete("delete from telemetry_point where tenant_id = #{tenantId}")
    int deleteTelemetryPointsByTenant(@Param("tenantId") UUID tenantId);

    @Delete("delete from telemetry_latest where tenant_id = #{tenantId}")
    int deleteTelemetryLatestByTenant(@Param("tenantId") UUID tenantId);

    @Delete("delete from device_attribute where tenant_id = #{tenantId}")
    int deleteAttributesByTenant(@Param("tenantId") UUID tenantId);

    @Delete("delete from device_credentials where device_id in (select id from device where tenant_id = #{tenantId})")
    int deleteDeviceCredentialsByTenant(@Param("tenantId") UUID tenantId);

    @Delete("delete from device where tenant_id = #{tenantId}")
    int deleteDevicesByTenant(@Param("tenantId") UUID tenantId);

    @Update("""
            update device_profile
            set firmware_id = null, software_id = null
            where tenant_id = #{tenantId}
            """)
    int clearProfileOtaReferences(@Param("tenantId") UUID tenantId);

    @Delete("delete from ota_package_artifact_chunk where package_id in (select id from ota_package where tenant_id = #{tenantId})")
    int deleteOtaChunksByTenant(@Param("tenantId") UUID tenantId);

    @Delete("delete from ota_package where tenant_id = #{tenantId}")
    int deleteOtaPackagesByTenant(@Param("tenantId") UUID tenantId);

    @Delete("delete from device_profile where tenant_id = #{tenantId}")
    int deleteDeviceProfilesByTenant(@Param("tenantId") UUID tenantId);

    @Delete("""
            delete from tenant_profile profile
            where profile.id = #{profileId}
              and not profile.is_default
              and not exists (
                  select 1 from tenant where tenant_profile_id = profile.id
              )
            """)
    int deleteOrphanTenantProfile(@Param("profileId") UUID profileId);
}
