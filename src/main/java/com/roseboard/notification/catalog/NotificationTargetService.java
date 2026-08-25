package com.roseboard.notification.catalog;

import com.roseboard.common.PageData;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.roseboard.infrastructure.notification.model.ChannelKind;
import com.roseboard.infrastructure.notification.NotificationCenter;
import com.roseboard.infrastructure.notification.model.NotifyCommand;
import com.roseboard.infrastructure.notification.model.NotifyOptions;
import com.roseboard.infrastructure.notification.model.NotifyResult;
import com.roseboard.infrastructure.notification.model.RecipientRef;
import com.roseboard.infrastructure.notification.spi.NotificationTemplateSource;
import com.roseboard.notification.template.TemplateService;
import com.roseboard.user.UserEntity;
import com.roseboard.user.UserMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
@Service
public class NotificationTargetService {
    private static final int MAX_TARGET_USERS = 1_000;

    private final NotificationTargetMapper targetMapper;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;
    private final NotificationCenter notificationCenter;
    private final NotificationTemplateSource templateSource;

    public NotificationTargetService(NotificationTargetMapper targetMapper, UserMapper userMapper, ObjectMapper objectMapper,
                                     NotificationCenter notificationCenter, NotificationTemplateSource templateSource) {
        this.targetMapper = targetMapper;
        this.userMapper = userMapper;
        this.objectMapper = objectMapper;
        this.notificationCenter = notificationCenter;
        this.templateSource = templateSource;
    }

    public List<NotificationTargetEntity> listTargets(UUID tenantId) {
        return targetMapper.selectByTenant(tenantId);
    }

    public List<NotificationTargetEntity> listTargetsByIds(UUID tenantId, UUID[] ids) {
        if (ids == null || ids.length == 0) {
            return List.of();
        }
        return targetMapper.selectByTenantAndIds(tenantId, ids);
    }

    public PageData<NotificationTargetEntity> pageTargets(UUID tenantId, long pageSize, long page, String textSearch) {
        LambdaQueryWrapper<NotificationTargetEntity> query = new LambdaQueryWrapper<NotificationTargetEntity>()
                .eq(NotificationTargetEntity::getTenantId, tenantId)
                .orderByAsc(NotificationTargetEntity::getName);
        if (textSearch != null && !textSearch.isBlank()) {
            query.like(NotificationTargetEntity::getName, textSearch);
        }
        Page<NotificationTargetEntity> result = targetMapper.selectPage(new Page<>(page + 1L, pageSize), query);
        return new PageData<>(result.getRecords(), pageSize, page, result.getTotal());
    }

    public PageData<RecipientRef> recipients(UUID tenantId, JsonNode payload, long pageSize, long page) {
        ParsedTarget parsed = parseTarget(payload);
        NotificationTargetEntity target = new NotificationTargetEntity();
        target.setTenantId(tenantId);
        target.setTargetType(parsed.type().name());
        target.setUserIds(parsed.userIds());
        target.setConfiguration(parsed.configuration());
        if (parsed.type() == TargetType.ALL_USERS || parsed.type() == TargetType.CUSTOMER_USERS) {
            Page<UserEntity> result = pageTargetUsers(tenantId, target, pageSize, page);
            return new PageData<>(NotificationRecipients.toRecipients(result.getRecords()), pageSize, page,
                    result.getTotal());
        }
        List<RecipientRef> recipients = resolveTargetRecipients(tenantId, target);
        int from = Math.toIntExact(Math.min(Math.max(page, 0) * Math.max(pageSize, 1), recipients.size()));
        int size = Math.toIntExact(Math.max(pageSize, 1));
        int to = Math.min(from + size, recipients.size());
        return new PageData<>(recipients.subList(from, to), pageSize, page, recipients.size());
    }

    public NotificationTargetEntity getTarget(UUID tenantId, UUID id) {
        NotificationTargetEntity entity = targetMapper.selectByTenantAndId(tenantId, id);
        if (entity == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification target not found");
        }
        return entity;
    }

    @Transactional(rollbackFor = Exception.class)
    public NotificationTargetEntity saveTarget(UUID tenantId, UUID id, JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Target payload must be an object");
        }
        String name = payload.path("name").asText("");
        if (!StringUtils.hasText(name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        ParsedTarget parsed = parseTarget(payload);
        long now = System.currentTimeMillis();
        NotificationTargetEntity entity = id == null ? new NotificationTargetEntity() : getTarget(tenantId, id);
        if (id == null) {
            entity.setId(UUID.randomUUID());
            entity.setTenantId(tenantId);
            entity.setCreatedTime(now);
        }
        entity.setName(name.trim());
        entity.setTargetType(parsed.type().name());
        entity.setUserIds(parsed.userIds());
        entity.setConfiguration(parsed.configuration());
        entity.setUpdatedTime(now);
        if (id == null) {
            targetMapper.insert(entity);
        } else {
            targetMapper.updateById(entity);
        }
        return entity;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteTarget(UUID tenantId, UUID id) {
        targetMapper.deleteById(getTarget(tenantId, id).getId());
    }
    public List<RecipientRef> resolveRecipients(UUID tenantId, UUID targetId) {
        return resolveTargetRecipients(tenantId, getTarget(tenantId, targetId));
    }

    public NotifyResult send(UUID tenantId, JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Send payload must be an object");
        }
        String templateKey = payload.path("templateKey").asText("");
        if (!StringUtils.hasText(templateKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "templateKey is required");
        }

        if (TemplateService.isSystemKey(templateKey)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "System templates cannot be sent manually");
        }
        if (templateSource.find(tenantId, templateKey).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found: " + templateKey);
        }
        return notificationCenter.notify(tenantId, new NotifyCommand(
                parseChannels(payload.get("channels")),
                resolveSendRecipients(tenantId, payload),
                payload.path("type").asText("MANUAL"),
                templateKey,
                parseVars(payload.get("vars")),
                NotifyOptions.defaults()));
    }

    private List<RecipientRef> resolveSendRecipients(UUID tenantId, JsonNode payload) {
        if (payload.hasNonNull("targetId")) {
            return resolveTargetRecipients(tenantId,
                    getTarget(tenantId, UUID.fromString(payload.get("targetId").asText())));
        }
        JsonNode userIds = payload.get("userIds");
        if (userIds != null && userIds.isArray() && !userIds.isEmpty()) {
            return resolveUserIds(tenantId, parseUserIdSet(userIds));
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetId or userIds is required");
    }

    private List<RecipientRef> resolveTargetRecipients(UUID tenantId, NotificationTargetEntity target) {
        TargetType type = TargetType.valueOf(target.getTargetType());
        return switch (type) {
            case TENANT_ADMINS -> tenantAdmins(tenantId);
            case USER_IDS -> usersByIds(tenantId, target.getUserIds());
            case ALL_USERS -> allUsers(tenantId);
            case CUSTOMER_USERS -> usersByCustomer(tenantId, target.getConfiguration());
        };
    }

    private List<RecipientRef> resolveUserIds(UUID tenantId, Set<UUID> userIds) {
        if (userIds.size() > MAX_TARGET_USERS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Too many users in notification target");
        }
        List<UserEntity> users = userMapper.selectList(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getTenantId, tenantId)
                .in(UserEntity::getId, userIds));
        Map<UUID, UserEntity> usersById = new HashMap<>(users.size());
        users.forEach(user -> usersById.put(user.getId(), user));
        List<RecipientRef> recipients = new ArrayList<>(userIds.size());
        for (UUID userId : userIds) {
            UserEntity user = usersById.get(userId);
            if (user == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User is outside tenant scope: " + userId);
            }
            recipients.add(NotificationRecipients.from(user));
        }
        return recipients;
    }

    private Page<UserEntity> pageTargetUsers(UUID tenantId, NotificationTargetEntity target,
                                             long pageSize, long page) {
        long safePageSize = Math.max(1, Math.min(pageSize, MAX_TARGET_USERS));
        long safePage = Math.max(0, page);
        LambdaQueryWrapper<UserEntity> query = new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getTenantId, tenantId)
                .orderByAsc(UserEntity::getEmail);
        if (target.getTargetType().equals(TargetType.CUSTOMER_USERS.name())) {
            UUID customerId;
            try {
                customerId = UUID.fromString(target.getConfiguration().path("usersFilter")
                        .path("customerId").asText());
            } catch (IllegalArgumentException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "customerId is required");
            }
            query.eq(UserEntity::getCustomerId, customerId);
        }
        return userMapper.selectPage(new Page<>(safePage + 1, safePageSize), query);
    }

    private List<RecipientRef> tenantAdmins(UUID tenantId) {
        List<UserEntity> admins = NotificationRecipients.tenantAdminUsers(userMapper, tenantId);
        if (admins.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No tenant administrators found");
        }
        return NotificationRecipients.toRecipients(admins);
    }

    private List<RecipientRef> usersByIds(UUID tenantId, JsonNode userIdsNode) {
        return resolveUserIds(tenantId, parseUserIdSet(userIdsNode));
    }

    private List<RecipientRef> allUsers(UUID tenantId) {
        return NotificationRecipients.toRecipients(userMapper.selectList(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getTenantId, tenantId)
                .orderByAsc(UserEntity::getEmail)));
    }

    private List<RecipientRef> usersByCustomer(UUID tenantId, JsonNode configuration) {
        UUID customerId;
        try {
            customerId = UUID.fromString(configuration.path("usersFilter").path("customerId").asText());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "customerId is required");
        }
        return NotificationRecipients.toRecipients(userMapper.selectList(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getTenantId, tenantId)
                .eq(UserEntity::getCustomerId, customerId)
                .orderByAsc(UserEntity::getEmail)));
    }

    private ParsedTarget parseTarget(JsonNode payload) {
        JsonNode configuration = payload.get("configuration");
        if (configuration != null && configuration.isObject()) {
            ObjectNode copy = (ObjectNode) configuration.deepCopy();
            if (!"PLATFORM_USERS".equalsIgnoreCase(copy.path("type").asText())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Only PLATFORM_USERS notification targets are supported");
            }
            JsonNode filter = copy.get("usersFilter");
            String filterType = filter == null ? "" : filter.path("type").asText("").toUpperCase();
            return switch (filterType) {
                case "TENANT_ADMINISTRATORS" -> new ParsedTarget(TargetType.TENANT_ADMINS,
                        objectMapper.createArrayNode(), copy);
                case "ALL_USERS" -> new ParsedTarget(TargetType.ALL_USERS,
                        objectMapper.createArrayNode(), copy);
                case "CUSTOMER_USERS" -> {
                    if (!StringUtils.hasText(filter.path("customerId").asText())) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "customerId is required");
                    }
                    yield new ParsedTarget(TargetType.CUSTOMER_USERS, objectMapper.createArrayNode(), copy);
                }
                case "USER_LIST" -> new ParsedTarget(TargetType.USER_IDS,
                        parseTargetUserIds(filter == null ? null : filter.get("usersIds"),
                                TargetType.USER_IDS), copy);
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unsupported usersFilter type: " + filterType);
            };
        }
        TargetType type = parseTargetType(payload.path("targetType").asText(""));
        ObjectNode normalized = objectMapper.createObjectNode();
        normalized.put("type", "PLATFORM_USERS");
        ObjectNode filter = normalized.putObject("usersFilter");
        if (type == TargetType.TENANT_ADMINS) {
            filter.put("type", "TENANT_ADMINISTRATORS");
        } else if (type == TargetType.USER_IDS) {
            filter.put("type", "USER_LIST");
            filter.set("usersIds", payload.get("userIds"));
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported target type: " + type);
        }
        return new ParsedTarget(type, parseTargetUserIds(payload.get("userIds"), type), normalized);
    }

    private record ParsedTarget(TargetType type, ArrayNode userIds, ObjectNode configuration) {
    }

    private static Set<UUID> parseUserIdSet(JsonNode userIdsNode) {
        if (userIdsNode == null || !userIdsNode.isArray() || userIdsNode.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userIds must be a non-empty array");
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (JsonNode node : userIdsNode) {
            try {
                ids.add(UUID.fromString(node.asText()));
            } catch (IllegalArgumentException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid user id");
            }
        }
        if (ids.size() > MAX_TARGET_USERS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Too many users in notification target");
        }
        return ids;
    }

    private static TargetType parseTargetType(String raw) {
        try {
            return TargetType.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown targetType: " + raw);
        }
    }

    private ArrayNode parseTargetUserIds(JsonNode userIds, TargetType targetType) {
        if (targetType == TargetType.TENANT_ADMINS) {
            return objectMapper.createArrayNode();
        }
        if (userIds == null || !userIds.isArray() || userIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userIds is required for USER_IDS target");
        }
        if (userIds.size() > MAX_TARGET_USERS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Too many users in notification target");
        }
        ArrayNode copy = objectMapper.createArrayNode();
        for (JsonNode node : userIds) {
            copy.add(node.asText());
        }
        return copy;
    }

    private static Set<ChannelKind> parseChannels(JsonNode channelsNode) {
        if (channelsNode == null || !channelsNode.isArray() || channelsNode.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "channels must be a non-empty array");
        }
        Set<ChannelKind> channels = EnumSet.noneOf(ChannelKind.class);
        for (JsonNode node : channelsNode) {
            try {
                channels.add(ChannelKind.parse(node.asText()));
            } catch (IllegalArgumentException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
            }
        }
        return channels;
    }

    private Map<String, Object> parseVars(JsonNode varsNode) {
        if (varsNode == null || varsNode.isNull()) {
            return Map.of();
        }
        if (!varsNode.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "vars must be an object");
        }
        return objectMapper.convertValue(varsNode, Map.class);
    }
}
