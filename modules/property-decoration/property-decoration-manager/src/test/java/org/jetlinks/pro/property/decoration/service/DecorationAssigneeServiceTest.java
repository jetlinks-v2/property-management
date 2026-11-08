package org.jetlinks.pro.property.decoration.service;

import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.authorization.simple.SimpleUser;
import org.hswebframework.web.authorization.simple.SimplePermission;
import org.hswebframework.web.authorization.simple.SimpleDimension;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.core.command.AsyncProxyCommandSupport;
import org.jetlinks.pro.assets.scope.AssetScopeResolverManager;
import org.jetlinks.pro.assets.scope.AssetScope;
import org.jetlinks.pro.assets.scope.AssetScopeActivation;
import org.jetlinks.pro.assets.scope.AssetScopeContext;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.property.base.user.PropertyEmployeeInfo;
import org.jetlinks.pro.command.CommandSupportManagerProvider;
import org.jetlinks.pro.command.auth.GetAuthenticationCommand;
import org.jetlinks.sdk.server.SdkServices;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DecorationAssigneeServiceTest {
    private final DecorationAssigneeService service = new DecorationAssigneeService(
        mock(DecorationScope.class), new AssetScopeResolverManager());

    private Authentication auth(String id) {
        var user = new SimpleUser(); user.setId(id);
        var auth = new SimpleAuthentication(); auth.setUser(user); return auth;
    }

    private void withProvider(CommandSupport support, Runnable check) {
        ReflectionTestUtils.setField(service, "serverId", "public-auth");
        var previous = CommandSupportManagerProvider.supports.get(SdkServices.authService);
        var registration = CommandSupportManagerProvider.supports.register(SdkServices.authService,
            new CommandSupportManagerProvider() {
                public String getProvider() { return SdkServices.authService; }
                public Mono<? extends CommandSupport> getCommandSupport(String module, Map<String, Object> options) {
                    return Mono.deferContextual(context -> {
                        assertTrue(context.stream().anyMatch(e -> "public-auth".equals(e.getValue())));
                        return Mono.just(support);
                    });
                }
            });
        try { check.run(); }
        finally {
            registration.dispose();
            previous.ifPresent(provider -> CommandSupportManagerProvider.supports.register(SdkServices.authService, provider));
        }
    }

    @Test void candidateAuthenticationUsesPinnedPublicCommandAndPreservesCaller() {
        var caller = auth("manager"); var candidate = auth("candidate");
        var delegate = CommandSupport.create(GetAuthenticationCommand::new, command -> {
            assertEquals("candidate", command.getUserId()); assertNull(command.getToken());
            return candidate;
        });
        var support = new AsyncProxyCommandSupport(Authentication.currentReactive().map(current -> {
            assertSame(caller, current); return delegate;
        }));
        withProvider(support, () -> StepVerifier.create(service.authentication("candidate")
                .contextWrite(context -> context.put(Authentication.class, caller)))
            .assertNext(result -> assertEquals("candidate", result.getUser().getId())).verifyComplete());
    }

    @Test void remoteDenialEmptyAndMismatchedCandidateNeverFallBackToCallerOrCache() {
        for (int scenario = 0; scenario < 3; scenario++) {
            int selected = scenario;
            var support = CommandSupport.create(GetAuthenticationCommand::new, command -> {
                if (selected == 0) throw new AccessDenyException();
                return selected == 1 ? null : auth("other");
            });
            withProvider(support, () -> StepVerifier.create(service.authentication("candidate"))
                .expectError(AccessDenyException.class).verify());
        }
    }

    @Test void unavailableCommandIsNotAnEmptyOrEligibleCandidate() {
        var support = CommandSupport.create(GetAuthenticationCommand::new, command -> (Authentication) null);
        withProvider(support, () -> StepVerifier.create(service.authentication("candidate"))
            .expectError(AccessDenyException.class).verify());
        var unsupported = CommandSupport.create(org.jetlinks.sdk.server.file.DownloadFileCommand::new, command -> reactor.core.publisher.Flux.empty());
        withProvider(unsupported, () -> StepVerifier.create(service.authentication("candidate"))
            .expectErrorSatisfies(error -> assertEquals(503, ((BusinessException) error).getStatus())).verify());
    }

    private record Candidate(DecorationAssigneeService service, DecorationScope directory,
                             AssetScopeActivation active) {}

    private Candidate candidate(boolean handle) {
        var directory = mock(DecorationScope.class);
        var resolver = mock(AssetScopeResolverManager.class);
        var candidate = (SimpleAuthentication) auth("candidate");
        candidate.setPermissions(handle ? List.of(SimplePermission.builder().id("decoration-task")
            .actions(Set.of("handle")).build()) : List.of());
        var scope = AssetScope.of(BusinessApplicationDimension.TYPE, "app");
        var active = AssetScopeActivation.activate(candidate,
            SimpleDimension.of("app", "app", BusinessApplicationDimension.INSTANCE, Map.of()), Map.of());
        when(resolver.resolve(eq(scope), any(Authentication.class))).thenReturn(Mono.just(active));
        var person = new PropertyEmployeeInfo("candidate", "候选员工", "", List.of("project"));
        when(directory.employees(any())).thenAnswer(call -> Mono.just(PagerResult.of(1, List.of(person),
            call.<QueryParamEntity>getArgument(0))));
        var service = new DecorationAssigneeService(directory, resolver) {
            @Override protected Mono<Authentication> authentication(String userId) { return Mono.just(candidate); }
        };
        return new Candidate(service, directory, active);
    }

    @Test void projectReadUsesCandidateScopeAtDirectoryAndRestoresCallerContext() {
        var fixture = candidate(true); var caller = auth("manager"); var callerHolder = mock(AssetsHolder.class);
        var project = new PropertyOrganizationInfo(); project.setId("project");
        when(fixture.directory.projects()).thenReturn(Flux.deferContextual(context -> {
            assertSame(fixture.active.authentication(), context.get(Authentication.class));
            assertFalse(context.hasKey(AssetsHolder.class));
            assertEquals("app", AssetScopeContext.current(context).getScope(BusinessApplicationDimension.TYPE).orElseThrow().id());
            return Flux.just(project);
        }));
        StepVerifier.create(fixture.service.eligible("candidate", "project",
                new DecorationScope.Context("tenant", "app", "manager", "指派人", caller))
            .flatMap(person -> Mono.deferContextual(context -> {
                assertSame(caller, context.get(Authentication.class));
                assertSame(callerHolder, context.get(AssetsHolder.class)); return Mono.just(person);
            })).contextWrite(context -> context.put(Authentication.class, caller).put(AssetsHolder.class, callerHolder)))
            .assertNext(person -> assertEquals("candidate", person.id())).verifyComplete();
    }

    @Test void candidateWithoutActionOrProjectIsRejectedAndDirectoryFailureIsNotMasked() {
        var ctx = new DecorationScope.Context("tenant", "app", "manager", "指派人", auth("manager"));
        var noAction = candidate(false);
        StepVerifier.create(noAction.service.eligible("candidate", "project", ctx))
            .expectErrorSatisfies(error -> assertEquals("error.decoration.assignee_application_permission_required", error.getMessage())).verify();
        verify(noAction.directory, never()).projects();
        var fixture = candidate(true);
        var other = new PropertyOrganizationInfo(); other.setId("other-project");
        when(fixture.directory.projects()).thenReturn(Flux.just(other));
        StepVerifier.create(fixture.service.eligible("candidate", "project", ctx))
            .expectErrorSatisfies(error -> assertEquals("error.decoration.assignee_project_permission_required", error.getMessage())).verify();
        when(fixture.directory.projects()).thenReturn(Flux.error(DecorationRules.error("directory_unavailable", 503)));
        StepVerifier.create(fixture.service.eligible("candidate", "project", ctx))
            .expectErrorSatisfies(error -> assertEquals(503, ((BusinessException) error).getStatus())).verify();
    }
}
