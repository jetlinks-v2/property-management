package org.jetlinks.pro.smartpark.repair.service;

import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.base.command.QueryPropertyUsersCommand;
import org.jetlinks.pro.property.base.command.QueryPropertySpacesCommand;
import org.jetlinks.pro.property.base.user.PropertyUserInfo;
import org.jetlinks.pro.property.base.space.PropertySpaceInfo;
import org.hswebframework.web.exception.BusinessException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证跨服务目录负载、身份实时变化、跨项目拒绝和服务故障语义。 */
class RepairDirectoryTest {
    @Test
    void identityChangeTakesEffectWithoutLegacyProjectionOrCache() {
        var support = mock(CommandSupport.class);
        var user = new PropertyUserInfo();user.setId("u");user.setStatus((byte)1);
        user.setIdentities(List.of("owner","employee"));
        doAnswer(call -> {
            QueryPropertyUsersCommand command = call.getArgument(0);
            assertEquals(List.of("u"),command.getIds());
            return Flux.just(user);
        }).when(support).execute(any(QueryPropertyUsersCommand.class));
        try (var providers=mockStatic(CommandSupportManagerProviders.class)) {
            providers.when(() -> CommandSupportManagerProviders.getCommandSupport(QueryPropertyUsersCommand.SERVICE_ID))
                .thenReturn(Mono.just(support));
            var directory = new RepairDirectory(null,null);
            StepVerifier.create(directory.employee("u")).expectNext(user).verifyComplete();
            user.setIdentities(List.of("owner"));
            StepVerifier.create(directory.employee("u")).expectErrorMatches(error ->
                error instanceof BusinessException && error.getMessage().equals("error.repair.employee_required")).verify();
            user.setIdentities(List.of("employee"));user.setStatus((byte)0);
            StepVerifier.create(directory.employee("u")).expectError(BusinessException.class).verify();
            verify(support,times(3)).execute(any(QueryPropertyUsersCommand.class));
        }
    }
    @Test
    void projectScopedSpaceCannotBeSelectedFromAnotherRootBinding() {
        var support=mock(CommandSupport.class);
        var space=new PropertySpaceInfo();space.setId("space-a");space.setOrganizationId("project-a");
        doAnswer(call -> {
            QueryPropertySpacesCommand command=call.getArgument(0);
            return command.getProjectId().equals("project-a") ? Flux.just(space) : Flux.empty();
        }).when(support).execute(any(QueryPropertySpacesCommand.class));
        try(var providers=mockStatic(CommandSupportManagerProviders.class)) {
            providers.when(() -> CommandSupportManagerProviders.getCommandSupport(QueryPropertyUsersCommand.SERVICE_ID))
                .thenReturn(Mono.just(support));
            var directory=new RepairDirectory(null,null);
            StepVerifier.create(directory.space("project-a","space-a")).expectNext(space).verifyComplete();
            StepVerifier.create(directory.space("project-b","space-a")).expectErrorMatches(error ->
                error instanceof BusinessException && error.getMessage().equals("error.repair.space_project_mismatch")).verify();
        }
    }
    @Test
    void missingProviderIsExplicitFailureWithoutEmptyOrLegacyFallback() {
        try(var providers=mockStatic(CommandSupportManagerProviders.class)) {
            providers.when(() -> CommandSupportManagerProviders.getCommandSupport(QueryPropertyUsersCommand.SERVICE_ID))
                .thenReturn(Mono.empty());
            StepVerifier.create(new RepairDirectory(null,null).employees()).expectErrorMatches(error ->
                error instanceof BusinessException business && business.getStatus()==503).verify();
        }
    }
}
