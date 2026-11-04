package org.jetlinks.pro.smartpark.repair.service;

import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.base.command.QueryPropertyUsersCommand;
import org.jetlinks.pro.property.base.user.PropertyUserInfo;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepairRegistrationTest {
    @Test
    void phoneComesFromCanonicalUserWithoutCloudUserMenuAndFailureIsPropagated() {
        var context = new RepairContext("tenant", "app", "employee", "登记员工");
        var order = new RepairOrderEntity();
        order.setContactPhone("13800000000");
        var support = mock(CommandSupport.class);
        var user = new PropertyUserInfo();
        user.setId("employee");
        user.setTelephone("13900000000");
        doAnswer(call -> {
            QueryPropertyUsersCommand command = call.getArgument(0);
            assertEquals(List.of("employee"), command.readable().get("ids"));
            return Flux.just(user);
        }).when(support).execute(any(QueryPropertyUsersCommand.class));
        var intake = new RepairIntakeService(null, null, new RepairDirectory(null, null));
        try (var commands = mockStatic(CommandSupportManagerProviders.class)) {
            commands.when(() -> CommandSupportManagerProviders.getCommandSupport(QueryPropertyUsersCommand.SERVICE_ID))
                    .thenReturn(Mono.just(support));
            StepVerifier.create(intake.registrationPhone(order, context)).verifyComplete();
            assertEquals("13900000000", order.getRegisterPhone());
            assertEquals("13800000000", order.getContactPhone());
            user.setTelephone(null);
            StepVerifier.create(intake.registrationPhone(order, context)).verifyComplete();
            assertNull(order.getRegisterPhone());
            commands.when(() -> CommandSupportManagerProviders.getCommandSupport(QueryPropertyUsersCommand.SERVICE_ID))
                    .thenReturn(Mono.error(new IllegalStateException("directory unavailable")));
            StepVerifier.create(intake.registrationPhone(new RepairOrderEntity(), context))
                    .expectErrorMessage("directory unavailable").verify();
        }
    }
}
