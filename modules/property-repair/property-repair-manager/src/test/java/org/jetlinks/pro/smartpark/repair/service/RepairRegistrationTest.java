package org.jetlinks.pro.smartpark.repair.service;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.pro.command.CommandServices;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import org.jetlinks.sdk.server.SdkServices;
import org.jetlinks.sdk.server.auth.AuthCommandSupportTypes;
import org.jetlinks.sdk.server.auth.UserDetail;
import org.jetlinks.sdk.server.auth.cmd.QueryUserDetailPageCommand;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepairRegistrationTest {
    @Test void phoneComesFromCurrentTenantUserAndRemoteFailureDoesNotBorrowReporterPhone() {
        var context=new RepairContext("tenant","app","employee","登记员工");
        var order=new RepairOrderEntity();order.setContactPhone("13800000000");
        var support=mock(CommandSupport.class);var captured=new AtomicReference<QueryUserDetailPageCommand>();
        // 跨进程分页响应的元素是Map，不能只用本地已类型化对象替代真实边界。
        var user=java.util.Map.of("id","employee","telephone","13900000000");
        doAnswer(call -> {captured.set(call.getArgument(0));return Mono.just(PagerResult.of(1,List.of(user)));})
            .when(support).execute(any(QueryUserDetailPageCommand.class));
        var intake=new RepairIntakeService(null,null);
        try(var commands=mockStatic(CommandServices.class)) {
            commands.when(() -> CommandServices.getReactive(SdkServices.authService,AuthCommandSupportTypes.user)).thenReturn(Mono.just(support));
            StepVerifier.create(intake.registrationPhone(order,context)).verifyComplete();
            assertEquals("13900000000",order.getRegisterPhone());assertEquals("13800000000",order.getContactPhone());
            var query=captured.get().asQueryParam();assertEquals(1,query.getPageSize());
            assertTrue(query.getTerms().stream().anyMatch(term -> "id".equals(term.getColumn())&&"employee".equals(term.getValue())));
            assertTrue(query.getTerms().stream().anyMatch(term -> "tenantId".equals(term.getColumn())&&"tenant".equals(term.getValue())));
            commands.when(() -> CommandServices.getReactive(SdkServices.authService,AuthCommandSupportTypes.user))
                .thenReturn(Mono.error(new IllegalStateException("auth unavailable")));
            StepVerifier.create(intake.registrationPhone(new RepairOrderEntity(),context)).expectErrorMessage("auth unavailable").verify();
        }
    }
}
