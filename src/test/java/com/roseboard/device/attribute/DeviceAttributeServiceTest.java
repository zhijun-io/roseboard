package com.roseboard.device.attribute;

import com.roseboard.user.UserAuthority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import com.roseboard.device.DeviceMapper;

@ExtendWith(MockitoExtension.class)
class DeviceAttributeServiceTest {
    @Mock DeviceAttributeMapper mapper;
    @Mock DeviceMapper deviceMapper;
    @Mock ApplicationEventPublisher events;

    @Test
    void rejectsOversizedBatchBeforeWriting() {
        DeviceAttributeService service = new DeviceAttributeService(
                mapper, deviceMapper, new AttributeAuthorizationService(), events);
        List<AttributeWrite> writes = java.util.stream.IntStream.range(0, 101)
                .mapToObj(index -> new AttributeWrite(AttributeScope.SERVER,
                        new AttributeKey("key" + index), new AttributeValue(index), null))
                .toList();

        assertThrows(IllegalArgumentException.class, () -> service.writeBatch(
                server(), UUID.randomUUID(), UUID.randomUUID(), writes, AttributeBatchMode.ATOMIC));
    }

    private static UsernamePasswordAuthenticationToken server() {
        return new UsernamePasswordAuthenticationToken("server", "n/a",
                List.of(new SimpleGrantedAuthority(UserAuthority.TENANT_ADMIN.name())));
    }
}
