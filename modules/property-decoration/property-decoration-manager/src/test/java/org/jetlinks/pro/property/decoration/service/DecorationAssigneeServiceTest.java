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
import org.jetlinks.pro.authorize.OrgDimensionType;
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
        var auth = new SimpleAuthentication(); auth.setUser(user);
        auth.setDimensions(List.of(SimpleDimension.of("org", "本组织", OrgDimensionType.org, Map.of()),
            SimpleDimension.of("child", "子组织", OrgDimensionType.org, Map.of()),
            SimpleDimension.of("parent", "上级", OrgDimensionType.parentOrg, Map.of())));
        return auth;
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
            @Override protected Mono<List<String>> applicationMemberIds(String app, List<String> selected) {
                assertEquals("app", app);
                return Mono.just(selected.isEmpty() || selected.contains("candidate") ? List.of("candidate") : List.of());
            }
        };
        return new Candidate(service, directory, active);
    }

    @Test void projectReadUsesCandidateScopeAtDirectoryAndRestoresCallerContext() {
        var fixture = candidate(true); var caller = auth("manager"); var callerHolder = mock(AssetsHolder.class);
        when(fixture.directory.canReadProject("project")).thenReturn(Mono.deferContextual(context -> {
            assertSame(fixture.active.authentication(), context.get(Authentication.class));
            assertFalse(context.hasKey(AssetsHolder.class));
            assertEquals("app", AssetScopeContext.current(context).getScope(BusinessApplicationDimension.TYPE).orElseThrow().id());
            return Mono.just(true);
        }));
        StepVerifier.create(fixture.service.eligible("candidate", "project",
                new DecorationScope.Context("tenant", "app", "manager", "指派人", caller))
            .flatMap(person -> Mono.deferContextual(context -> {
                assertSame(caller, context.get(Authentication.class));
                assertSame(callerHolder, context.get(AssetsHolder.class)); return Mono.just(person);
            })).contextWrite(context -> context.put(Authentication.class, caller).put(AssetsHolder.class, callerHolder)))
            .assertNext(person -> assertEquals("candidate", person.id())).verifyComplete();
        verify(fixture.directory, never()).projects();
    }

    @Test void candidateWithoutActionCanBeSelectedButProjectDenialAndDirectoryFailureArePreserved() {
        var ctx = new DecorationScope.Context("tenant", "app", "manager", "指派人", auth("manager"));
        var noAction = candidate(false);
        when(noAction.directory.canReadProject("project")).thenReturn(Mono.just(true));
        StepVerifier.create(noAction.service.eligible("candidate", "project", ctx))
            .assertNext(person -> assertEquals("candidate", person.id())).verifyComplete();
        var fixture = candidate(true);
        when(fixture.directory.canReadProject("project")).thenReturn(Mono.just(false));
        StepVerifier.create(fixture.service.eligible("candidate", "project", ctx))
            .expectErrorSatisfies(error -> assertEquals("error.decoration.assignee_project_permission_required", error.getMessage())).verify();
        when(fixture.directory.canReadProject("project")).thenReturn(Mono.error(DecorationRules.error("directory_unavailable", 503)));
        StepVerifier.create(fixture.service.eligible("candidate", "project", ctx))
            .expectErrorSatisfies(error -> assertEquals(503, ((BusinessException) error).getStatus())).verify();
    }
    @Test void applicationMembersAreNestedOutsideCallerOrTermsAndRecheckedAtAssignment() {
        var fixture = candidate(false);
        var input = QueryParamEntity.newQuery().where("name", "张三").or("id", "outsider").getParam();
        doAnswer(call -> {
            QueryParamEntity query = call.getArgument(0);
            var relation = query.getTerms().stream().filter(term -> "in".equals(term.getTermType()) && "id".equals(term.getColumn())).findFirst();
            assertTrue(relation.isPresent(), query.getTerms().toString());
            assertEquals(List.of("candidate"), relation.orElseThrow().getValue());
            assertTrue(query.getTerms().stream().noneMatch(term -> term.getType() == org.hswebframework.ezorm.core.param.Term.Type.or));
            assertTrue(query.getTerms().stream().noneMatch(term -> "property-user-org".equals(term.getTermType())));
            return Mono.just(PagerResult.of(0, List.of(), query));
        }).when(fixture.directory).employees(any());
        var ctx = new DecorationScope.Context("tenant", "app", "manager", "指派人", auth("manager"));
        StepVerifier.create(fixture.service.query("project", input, ctx))
            .assertNext(page -> assertEquals(0, page.getTotal())).verifyComplete();
        StepVerifier.create(fixture.service.eligible("outside", "project", ctx))
            .expectErrorSatisfies(error -> assertEquals("error.decoration.assignee_employee_scope_required", error.getMessage())).verify();
        verify(fixture.directory, never()).projects();
        verify(fixture.directory, never()).canReadProject(anyString());
    }

    @Test void projectFilteringPrecedesPaginationAndBroadSearchFailsExplicitly() {
        var fixture = candidate(false);
        when(fixture.directory.canReadProject("project")).thenReturn(Mono.just(true));
        var ctx = new DecorationScope.Context("tenant", "app", "manager", "指派人", auth("manager"));
        var input = new QueryParamEntity(); input.setPageSize(1); input.setPageIndex(1);
        StepVerifier.create(fixture.service.query("project", input, ctx))
            .assertNext(page -> { assertEquals(1, page.getTotal()); assertTrue(page.getData().isEmpty()); }).verifyComplete();
        when(fixture.directory.canReadProject("project")).thenReturn(Mono.just(false));
        input.setPageIndex(0);
        StepVerifier.create(fixture.service.query("project", input, ctx))
            .assertNext(page -> { assertEquals(0, page.getTotal()); assertTrue(page.getData().isEmpty()); }).verifyComplete();
        doReturn(Mono.just(PagerResult.of(1001, List.of(), input))).when(fixture.directory).employees(any());
        StepVerifier.create(fixture.service.query("project", input, ctx))
            .expectErrorSatisfies(error -> assertEquals("error.decoration.assignee_search_too_broad", error.getMessage())).verify();
    }

    @Test void crossPageProjectFilteringProducesRealTotalAndPreservesSourceOrder() {
        var directory = mock(DecorationScope.class);
        var resolver = mock(AssetScopeResolverManager.class);
        var people = java.util.stream.IntStream.rangeClosed(1, 201)
            .mapToObj(i -> new PropertyEmployeeInfo("user-" + i, "员工" + i, "", List.of("child"))).toList();
        when(directory.employees(any())).thenAnswer(call -> {
            QueryParamEntity query = call.getArgument(0);
            assertEquals(200, query.getPageSize());
            int start = query.getPageIndex() * 200;
            return Mono.just(PagerResult.of(201, people.subList(start, Math.min(201, start + 200)), query));
        });
        when(resolver.resolve(any(AssetScope.class), any(Authentication.class))).thenAnswer(call -> Mono.just(AssetScopeActivation.activate(
            call.getArgument(1), SimpleDimension.of("app", "app", BusinessApplicationDimension.INSTANCE, Map.of()), Map.of())));
        when(directory.canReadProject("project")).thenReturn(Mono.deferContextual(context -> {
            String id = context.<Authentication>get(Authentication.class).getUser().getId();
            return Mono.just(Set.of("user-2", "user-201").contains(id));
        }));
        var service = new DecorationAssigneeService(directory, resolver) {
            @Override protected Mono<Authentication> authentication(String id) { return Mono.just(auth(id)); }
            @Override protected Mono<List<String>> applicationMemberIds(String app, List<String> selected) {
                return Mono.just(people.stream().map(PropertyEmployeeInfo::id).toList());
            }
        };
        var input = new QueryParamEntity(); input.setPageIndex(1); input.setPageSize(1);
        StepVerifier.create(service.query("project", input,
                new DecorationScope.Context("tenant", "app", "manager", "指派人", auth("manager"))))
            .assertNext(page -> { assertEquals(2, page.getTotal()); assertEquals("user-201", page.getData().get(0).id()); })
            .verifyComplete();
        verify(directory, times(2)).employees(any());
        verify(directory, never()).projects();
    }

    @Test void noApplicationMembersNeverQueriesOrAllowsAnAssignee() {
        var directory = mock(DecorationScope.class);
        var service = new DecorationAssigneeService(directory, new AssetScopeResolverManager()) {
            @Override protected Mono<List<String>> applicationMemberIds(String app, List<String> selected) { return Mono.just(List.of()); }
        };
        var caller = (SimpleAuthentication) auth("manager"); caller.setDimensions(List.of());
        var ctx = new DecorationScope.Context("tenant", "app", "manager", "指派人", caller);
        StepVerifier.create(service.query("project", new QueryParamEntity(), ctx))
            .assertNext(page -> assertEquals(0, page.getTotal())).verifyComplete();
        StepVerifier.create(service.eligible("candidate", "project", ctx))
            .expectErrorSatisfies(error -> assertEquals("error.decoration.assignee_employee_scope_required", error.getMessage())).verify();
        verify(directory, never()).employees(any());
    }


    private java.util.stream.Stream<org.hswebframework.ezorm.core.param.Term> terms(List<org.hswebframework.ezorm.core.param.Term> list) {
        return list.stream().flatMap(term -> java.util.stream.Stream.concat(java.util.stream.Stream.of(term), terms(term.getTerms())));
    }

    @Test void membershipQueryUsesEnabledApplicationMembersAndCallerUserAssets() {
        var caller = auth("manager");
        var holder = new org.jetlinks.pro.assets.impl.CreatorAssetsHolder(caller.getUser(), mock(org.jetlinks.pro.assets.AssetManager.class));
        var service = new DecorationAssigneeService(mock(DecorationScope.class), new AssetScopeResolverManager()) {
            @Override protected Flux<org.jetlinks.sdk.server.auth.UserInfo> executeMembers(
                    org.jetlinks.sdk.server.commons.cmd.QueryListCommand<org.jetlinks.sdk.server.auth.UserInfo> command) {
                var query = command.asQueryParam(); var all = terms(query.getTerms()).toList();
                assertTrue(all.stream().anyMatch(term -> "in-dimension".equals(term.getTermType()) && List.of("app").equals(term.getValue())));
                assertTrue(all.stream().anyMatch(term -> "status".equals(term.getColumn()) && Integer.valueOf(1).equals(term.getValue())));
                assertTrue(all.stream().anyMatch(term -> List.of("candidate").equals(term.getValue())));
                assertTrue(all.stream().anyMatch(term -> "manager".equals(term.getValue())));
                var member = new org.jetlinks.sdk.server.auth.UserInfo(); member.setId("candidate");
                return Flux.just(member);
            }
        };
        StepVerifier.create(service.applicationMemberIds("app", List.of("candidate"))
                .contextWrite(context -> context.put(Authentication.class, caller).put(AssetsHolder.class, holder)))
            .expectNext(List.of("candidate")).verifyComplete();
    }

    @Test void membershipLookupFailureDoesNotFallBackToEmployeeDirectory() {
        var directory = mock(DecorationScope.class);
        var service = new DecorationAssigneeService(directory, new AssetScopeResolverManager()) {
            @Override protected Mono<List<String>> applicationMemberIds(String app, List<String> selected) {
                return Mono.error(DecorationRules.error("assignee_directory_unavailable", 503));
            }
        };
        var ctx = new DecorationScope.Context("tenant", "app", "manager", "指派人", auth("manager"));
        StepVerifier.create(service.query("project", new QueryParamEntity(), ctx))
            .expectErrorSatisfies(error -> assertEquals(503, ((BusinessException)error).getStatus())).verify();
        StepVerifier.create(service.eligible("candidate", "project", ctx))
            .expectErrorSatisfies(error -> assertEquals(503, ((BusinessException)error).getStatus())).verify();
        verifyNoInteractions(directory);
    }
}
