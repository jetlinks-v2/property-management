package org.jetlinks.pro.smartpark.handover.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.scope.AssetScope;
import org.jetlinks.pro.assets.scope.AssetScopeContext;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.io.file.FileManager;
import org.jetlinks.pro.property.base.command.*;
import org.jetlinks.pro.property.base.space.PropertySpaceInfo;
import org.jetlinks.pro.smartpark.handover.api.FileRef;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Set;

/** 公共命令、文件以及租户/应用资产的实际边界，不把故障当成空目录成功。 */
class HandoverBoundaryTest {
    @Test
    void realTenantHolderRejectsApplicationOwnedByAnotherTenant() {
        var manager = mock(AssetManager.class);
        var scope = new HandoverScope(manager);
        var app =
                new DefaultAsset("application", "查验", AssetType.of("business_application"))
                        .withOption("tenantId", "tenant");
        when(manager.getAssets(any(), anyCollection())).thenReturn(Flux.just(app));
        var active = AssetScopeContext.of(AssetScope.of("business_application", "application"));
        var current =
                scope.current()
                        .contextWrite(TenantHolder.makeCurrent("tenant"))
                        .contextWrite(AssetScopeContext.replace(active))
                        .contextWrite(
                                HandoverPersistenceFixture.access("tester", Set.of("project")));
        StepVerifier.create(current)
                .assertNext(context -> assertEquals("application", context.applicationId()))
                .verifyComplete();
        app.withOption("tenantId", "foreign-tenant");
        StepVerifier.create(current).expectError(AccessDenyException.class).verify();
        StepVerifier.create(scope.current()).expectError(AccessDenyException.class).verify();
    }

    @Test
    void directoryCommandFailureIsVisibleAndSpaceCannotCrossProjects() {
        var scope = mock(HandoverScope.class);
        when(scope.project(anyString(), any())).thenReturn(Mono.empty());
        var support = mock(CommandSupport.class);
        doAnswer(
                        call -> {
                            var command = (QueryPropertySpacesCommand) call.getArgument(0);
                            var space = new PropertySpaceInfo();
                            space.setId("space-a");
                            return "project-a".equals(command.getProjectId())
                                    ? Flux.just(space)
                                    : Flux.empty();
                        })
                .when(support)
                .execute(any(QueryPropertySpacesCommand.class));
        try (var providers = mockStatic(CommandSupportManagerProviders.class)) {
            providers
                    .when(
                            () ->
                                    CommandSupportManagerProviders.getCommandSupport(
                                            QueryPropertyUsersCommand.SERVICE_ID))
                    .thenReturn(Mono.just(support));
            var directory = new HandoverDirectory(scope);
            StepVerifier.create(directory.space("project-a", "space-a"))
                    .expectNextCount(1)
                    .verifyComplete();
            StepVerifier.create(directory.space("project-b", "space-a"))
                    .expectError(BusinessException.class)
                    .verify();
            providers
                    .when(
                            () ->
                                    CommandSupportManagerProviders.getCommandSupport(
                                            GetCurrentPropertyEmployeeCommand.SERVICE_ID))
                    .thenReturn(Mono.empty());
            StepVerifier.create(directory.currentEmployee())
                    .expectErrorMatches(
                            error ->
                                    error instanceof BusinessException business
                                            && business.getStatus() == 503)
                    .verify();
        }
    }

    @Test
    void employeePagingTravelsInTheRealCommandPayloadWithoutDroppingLaterPages() {
        var scope = mock(HandoverScope.class);
        when(scope.project(anyString(), any())).thenReturn(Mono.empty());
        var support = mock(CommandSupport.class);
        doAnswer(
                        call -> {
                            QueryPropertyEmployeePageCommand command = call.getArgument(0);
                            var query = command.getQuery();
                            assertEquals(2, query.getPageIndex());
                            assertEquals(150, query.getPageSize());
                            assertTrue(
                                    query.getTerms().stream()
                                            .anyMatch(term -> "name".equals(term.getColumn())));
                            return Mono.just(PagerResult.of(450, List.of(), query));
                        })
                .when(support)
                .execute(any(QueryPropertyEmployeePageCommand.class));
        try (var providers = mockStatic(CommandSupportManagerProviders.class)) {
            providers
                    .when(
                            () ->
                                    CommandSupportManagerProviders.getCommandSupport(
                                            QueryPropertyEmployeePageCommand.SERVICE_ID))
                    .thenReturn(Mono.just(support));
            StepVerifier.create(new HandoverDirectory(scope).users("project", "王", 2, 150))
                    .assertNext(page -> assertEquals(450, page.getTotal()))
                    .verifyComplete();
        }
    }

    @Test
    void forgedFileIdIsRejectedBeforeFileContentsAreRequested() {
        var files = mock(FileManager.class);
        var service = new HandoverFiles(files);
        var photo = new FileRef();
        photo.setFileId("foreign-file");
        StepVerifier.create(
                        service.validate(List.of(photo))
                                .contextWrite(
                                        HandoverPersistenceFixture.access(
                                                "tester", Set.of("project"))))
                .expectError(AccessDenyException.class)
                .verify();
        verifyNoInteractions(files);
    }
}
