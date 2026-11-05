package org.jetlinks.pro.smartpark.handover.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.pro.command.CommandSupportManagerProvider;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.command.register.CommandServiceEndpointRegister;
import org.jetlinks.pro.property.base.command.QueryPropertyProjectsCommand;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.smartpark.handover.command.CreateHandoverProblemCommand;
import org.jetlinks.pro.smartpark.handover.command.HandoverCommandSupport;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

/** 使用真实平台注册器和查找逻辑，验证本机查验命令不会遮蔽远程基础目录。 */
class HandoverCommandRegistrationTest {
    @Test
    void localHandoverRegistrationKeepsRemoteProjectDirectoryReachable() {
        String baseProvider = QueryPropertyProjectsCommand.SERVICE_ID.split(":", 2)[0];
        String handoverProvider = CreateHandoverProblemCommand.SERVICE_ID.split(":", 2)[0];
        var providers = CommandSupportManagerProvider.supports;
        var previousBase = providers.get(baseProvider);
        var previousHandover = providers.get(handoverProvider);
        var project = new PropertyOrganizationInfo();
        project.setId("project-a");
        var remote = mock(CommandSupport.class);
        when(remote.execute(any(QueryPropertyProjectsCommand.class)))
                .thenReturn(Flux.just(project));
        var scope = mock(HandoverScope.class);
        when(scope.current())
                .thenReturn(Mono.just(new HandoverContext("tenant", "app", "user", "用户")));
        try (var context = new GenericApplicationContext()) {
            providers.register(
                    baseProvider,
                    new CommandSupportManagerProvider() {
                        @Override
                        public String getProvider() {
                            return baseProvider;
                        }

                        @Override
                        public Mono<? extends CommandSupport> getCommandSupport(
                                String module, Map<String, Object> options) {
                            return "directory".equals(module) ? Mono.just(remote) : Mono.empty();
                        }
                    });
            context.registerBean(
                    HandoverCommandSupport.class,
                    () -> new HandoverCommandSupport(mock(HandoverProblemService.class)));
            context.refresh();
            var register = new CommandServiceEndpointRegister();
            register.setApplicationContext(context);
            register.afterSingletonsInstantiated();

            StepVerifier.create(new HandoverDirectory(scope).projects())
                    .assertNext(result -> assertEquals("project-a", result.getId()))
                    .verifyComplete();
            StepVerifier.create(
                            CommandSupportManagerProviders.getCommandSupport(
                                    CreateHandoverProblemCommand.SERVICE_ID))
                    .expectNextCount(1)
                    .verifyComplete();
            verify(remote).execute(any(QueryPropertyProjectsCommand.class));
        } finally {
            for (String id : List.of(baseProvider, handoverProvider)) providers.unregister(id);
            previousBase.ifPresent(value -> providers.register(baseProvider, value));
            previousHandover.ifPresent(value -> providers.register(handoverProvider, value));
        }
    }
}
