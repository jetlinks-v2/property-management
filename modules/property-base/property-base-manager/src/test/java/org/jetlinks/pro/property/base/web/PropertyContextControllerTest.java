package org.jetlinks.pro.property.base.web;

import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.ReactiveAuthenticationHolder;
import org.hswebframework.web.authorization.exception.UnAuthorizedException;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.authorization.simple.SimpleUser;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PropertyContextControllerTest {

    private final PropertyContextController controller = new PropertyContextController();

    @Test
    void anonymousCannotReadContextEvenWhenProjectIsPresent() {
        StepVerifier.create(controller.current()
                .contextWrite(TenantHolder.makeCurrent("residential-project-01"))
                .contextWrite(ReactiveAuthenticationHolder.ignoreContext(true)))
            .expectError(UnAuthorizedException.class)
            .verify(Duration.ofSeconds(5));
    }

    @Test
    void platformUserWithoutSelectedProjectGetsExplicitEmptyProject() {
        StepVerifier.create(controller.current()
                .contextWrite(ctx -> ctx.put(Authentication.class, user("platform-admin", "admin"))))
            .assertNext(result -> {
                assertEquals("platform-admin", result.userId());
                assertEquals("admin", result.username());
                assertNull(result.projectId());
            })
            .verifyComplete();
    }

    @Test
    void contextComesFromCurrentSubscriberAndDoesNotLeakBetweenProjects() {
        var request = controller.current();
        StepVerifier.create(request
                .contextWrite(TenantHolder.makeCurrent("residential-project-01"))
                .contextWrite(ctx -> ctx.put(Authentication.class, user("cashier-01", "cashier01"))))
            .assertNext(result -> {
                assertEquals("cashier-01", result.userId());
                assertEquals("residential-project-01", result.projectId());
            })
            .verifyComplete();
        StepVerifier.create(request
                .contextWrite(TenantHolder.makeCurrent("residential-project-02"))
                .contextWrite(ctx -> ctx.put(Authentication.class, user("cashier-02", "cashier02"))))
            .assertNext(result -> {
                assertEquals("cashier-02", result.userId());
                assertEquals("residential-project-02", result.projectId());
            })
            .verifyComplete();
    }

    private Authentication user(String id, String username) {
        SimpleUser user = new SimpleUser();
        user.setId(id);
        user.setUsername(username);
        SimpleAuthentication authentication = new SimpleAuthentication();
        authentication.setUser(user);
        return authentication;
    }
}
