package org.jetlinks.pro.patrol.service;

import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.DefaultDimensionType;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.authorization.simple.SimpleDimension;
import org.hswebframework.web.authorization.simple.SimpleUser;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.patrol.integration.PatrolScope;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;

class PatrolRoleServiceTest {
    private final PatrolScope scope = mock(PatrolScope.class);
    private final PatrolRoleService roles = new PatrolRoleService(scope);

    private SimpleAuthentication identity(String application) {
        var auth = new SimpleAuthentication();
        var user = new SimpleUser(); user.setId("employee"); auth.setUser(user);
        auth.setPermissions(List.of());
        auth.setDimensions(List.of(SimpleDimension.of("inspector", "巡更员", DefaultDimensionType.role,
                application == null ? Map.of() : Map.of("applicationId", application))));
        when(scope.current()).thenReturn(Mono.just(new PatrolScope.Current("patrol-a")));
        return auth;
    }

    @Test void currentApplicationMemberCanExecuteWithoutRoleManagementPermission() {
        var auth = identity("patrol-a");
        StepVerifier.create(roles.currentRole("inspector").contextWrite(ctx -> ctx.put(Authentication.class, auth)))
                .expectNextMatches(role -> role.getId().equals("inspector") && role.getUserIds().equals(List.of("employee")))
                .verifyComplete();
    }

    @Test void otherApplicationAndPlatformRolesCannotBeUsedForPatrol() {
        for (String application : new String[]{"patrol-b", null}) {
            var auth = identity(application);
            StepVerifier.create(roles.currentRole("inspector").contextWrite(ctx -> ctx.put(Authentication.class, auth)))
                    .expectErrorMatches(error -> error instanceof BusinessException business
                            && "error.patrol.execution.unassigned".equals(business.getI18nCode())).verify();
        }
    }

    @Test void unassignedOrRemovedRoleCannotExecute() {
        var auth = identity("patrol-a"); auth.setDimensions(List.of());
        StepVerifier.create(roles.currentRole("inspector").contextWrite(ctx -> ctx.put(Authentication.class, auth)))
                .expectError(BusinessException.class).verify();
    }
}
