package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.pro.command.CommandSupportManagerProvider;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.command.register.CommandServiceEndpointRegister;
import org.jetlinks.pro.property.base.command.QueryPropertyProjectCommand;
import org.jetlinks.pro.property.base.organization.PropertyProjectInfo;
import org.jetlinks.pro.property.finance.command.ApplyFinanceContractsCommand;
import org.jetlinks.pro.property.finance.command.FinanceContractCommandSupport;
import org.jetlinks.pro.property.finance.command.FinanceContractReportCommandSupport;
import org.jetlinks.pro.property.finance.command.QueryFinanceContractReportCommand;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.LinkedHashMap;
import java.util.Map;

/** 使用真实平台注册器复现本地收费命令遮蔽远程基础项目服务的故障。 */
class FinanceCommandRegistrationTest {
    @Test
    void localFinanceCommandsKeepRemoteBaseProjectReachable() {
        var providers = CommandSupportManagerProvider.supports;
        var previous = new LinkedHashMap<String, CommandSupportManagerProvider>();
        for (String service : new String[] {
            QueryPropertyProjectCommand.SERVICE_ID,
            ApplyFinanceContractsCommand.SERVICE_ID,
            QueryFinanceContractReportCommand.SERVICE_ID
        }) {
            String provider = service.split(":", 2)[0];
            if (!previous.containsKey(provider)) {
                previous.put(provider, providers.get(provider).orElse(null));
            }
        }
        var remote = mock(CommandSupport.class);
        when(remote.execute(any(QueryPropertyProjectCommand.class)))
                .thenAnswer(call -> {
                    QueryPropertyProjectCommand command = call.getArgument(0);
                    return Mono.just(new PropertyProjectInfo(command.getProjectId(), "测试项目"));
                });
        try (var context = new GenericApplicationContext()) {
            providers.register("propertyService", new CommandSupportManagerProvider() {
                @Override
                public String getProvider() {
                    return "propertyService";
                }

                @Override
                public Mono<? extends CommandSupport> getCommandSupport(
                        String module, Map<String, Object> options) {
                    return "project".equals(module) ? Mono.just(remote) : Mono.empty();
                }
            });
            context.registerBean(FinanceContractCommandSupport.class,
                    () -> new FinanceContractCommandSupport(mock(ContractMirrorService.class)));
            context.registerBean(FinanceContractReportCommandSupport.class,
                    () -> new FinanceContractReportCommandSupport(mock(ContractReportService.class)));
            context.refresh();
            var register = new CommandServiceEndpointRegister();
            register.setApplicationContext(context);
            register.afterSingletonsInstantiated();

            var command = new QueryPropertyProjectCommand();
            command.setProjectId("project-a");
            StepVerifier.create(CommandSupportManagerProviders
                            .getCommandSupport(QueryPropertyProjectCommand.SERVICE_ID)
                            .flatMap(support -> support.execute(command)))
                    .assertNext(project -> assertEquals("project-a", project.id()))
                    .verifyComplete();
            for (String service : new String[] {
                ApplyFinanceContractsCommand.SERVICE_ID,
                QueryFinanceContractReportCommand.SERVICE_ID
            }) {
                StepVerifier.create(CommandSupportManagerProviders.getCommandSupport(service))
                        .expectNextCount(1)
                        .verifyComplete();
            }
            verify(remote).execute(command);
        } finally {
            previous.forEach((id, provider) -> {
                providers.unregister(id);
                if (provider != null) providers.register(id, provider);
            });
        }
    }
}
