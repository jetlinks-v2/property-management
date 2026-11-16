package org.jetlinks.pro.property.decoration.service;

import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.DimensionType;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.authorization.simple.*;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.scope.*;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.jetlinks.pro.property.base.user.PropertyEmployeeInfo;
import org.jetlinks.pro.property.decoration.entity.DecorationNotificationEntity;
import org.jetlinks.sdk.server.auth.UserInfo;
import org.jetlinks.sdk.server.commons.cmd.QueryListCommand;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DecorationNotificationRecipientsTest {
    private final DecorationScope directory=mock(DecorationScope.class);
    private final AssetScopeResolverManager resolver=mock(AssetScopeResolverManager.class);
    private final Map<String,Authentication> users=new HashMap<>();
    private final DecorationNotificationRecipients service=new DecorationNotificationRecipients(directory,resolver) {
        @Override protected Mono<Authentication> authentication(String id){return Mono.justOrEmpty(users.get(id));}
    };
    private SimpleAuthentication user(String id,Set<String> actions,String... roles){
        var auth=new SimpleAuthentication();var user=new SimpleUser();user.setId(id);auth.setUser(user);
        auth.setPermissions(List.of(SimplePermission.builder().id("decoration-task").actions(actions).build()));
        var dimensions=new ArrayList<org.hswebframework.web.authorization.Dimension>(Arrays.stream(roles).map(role->(org.hswebframework.web.authorization.Dimension)SimpleDimension.of(role,role,
            new DimensionType(){public String getId(){return "role";}public String getName(){return "角色";}},Map.of())).toList());
        dimensions.add(SimpleDimension.of("app","app",BusinessApplicationDimension.INSTANCE,Map.of()));auth.setDimensions(dimensions);
        return auth;
    }
    private DecorationNotificationEntity notice(String role){
        var n=new DecorationNotificationEntity();n.setTenantId("tenant");n.setApplicationId("app");n.setProjectId("project");n.setRoleId(role);n.setDispatcherId("manager");return n;
    }
    private void prepare(){
        when(resolver.resolve(eq(AssetScope.of(BusinessApplicationDimension.TYPE,"app")),any(Authentication.class)))
            .thenAnswer(call->Mono.just(AssetScopeActivation.activate(call.getArgument(1),
                SimpleDimension.of("app","app",BusinessApplicationDimension.INSTANCE,Map.of()),Map.of("user",AssetScopeStrategy.IGNORE))));
        when(directory.employee()).thenReturn(Mono.just(new PropertyEmployeeInfo("worker","员工","",List.of("project"))));
        when(directory.canReadProject("project")).thenReturn(Mono.just(true));
    }
    @Test void receiverMustHaveActionRoleEmployeeAndProjectAndUsesOwnScope(){
        prepare();var employee=user("worker",Set.of("query","review"),"reviewer");users.put("worker",employee);
        var caller=user("owner",Set.of());var holder=mock(AssetsHolder.class);
        when(directory.canReadProject("project")).thenReturn(Mono.deferContextual(context->{
            assertEquals("worker",context.<Authentication>get(Authentication.class).getUser().getId());assertFalse(context.hasKey(AssetsHolder.class));
            assertEquals("app",AssetScopeContext.current(context).getScope(BusinessApplicationDimension.TYPE).orElseThrow().id());return Mono.just(true);
        }));
        StepVerifier.create(service.allowed("worker",notice("reviewer")).flatMap(result->Mono.deferContextual(context->{
            assertSame(caller,context.get(Authentication.class));assertSame(holder,context.get(AssetsHolder.class));return Mono.just(result);
        })).contextWrite(context->context.put(Authentication.class,caller).put(AssetsHolder.class,holder))).expectNext(true).verifyComplete();
        users.put("worker",user("worker",Set.of("query","review"),"different"));
        StepVerifier.create(service.allowed("worker",notice("reviewer"))).expectNext(false).verifyComplete();
        users.put("worker",user("worker",Set.of("query"),"reviewer"));
        StepVerifier.create(service.allowed("worker",notice("reviewer"))).expectNext(false).verifyComplete();
        users.put("worker",user("worker",Set.of("query","handle")));
        StepVerifier.create(service.allowed("worker",notice(null))).expectNext(true).verifyComplete();
        when(directory.canReadProject("project")).thenReturn(Mono.just(false));
        StepVerifier.create(service.allowed("worker",notice(null))).expectNext(false).verifyComplete();
    }
    @Test void revokedOrMismatchedIdentityIsDeniedButDirectoryOutageIsRetryable(){
        prepare();StepVerifier.create(service.allowed("missing",notice(null))).expectNext(false).verifyComplete();
        users.put("worker",user("another",Set.of("query","handle")));
        StepVerifier.create(service.allowed("worker",notice(null))).expectNext(false).verifyComplete();
        users.put("worker",user("worker",Set.of("query","handle")));
        var removed=user("worker",Set.of("query","handle"));removed.setDimensions(List.of());users.put("worker",removed);
        StepVerifier.create(service.allowed("worker",notice(null))).expectNext(false).verifyComplete();
        users.put("worker",user("worker",Set.of("query","handle")));
        when(directory.employee()).thenReturn(Mono.error(DecorationRules.error("employee_required",403)));
        StepVerifier.create(service.allowed("worker",notice(null))).expectNext(false).verifyComplete();
        when(directory.employee()).thenReturn(Mono.error(DecorationRules.error("directory_unavailable",503)));
        StepVerifier.create(service.allowed("worker",notice(null))).expectErrorMatches(e->e.getMessage().contains("directory_unavailable")).verify();
        when(resolver.resolve(any(AssetScope.class),any(Authentication.class))).thenReturn(Mono.error(new AccessDenyException()));
        StepVerifier.create(service.allowed("worker",notice(null))).expectNext(false).verifyComplete();
    }
    private java.util.stream.Stream<org.hswebframework.ezorm.core.param.Term> flatten(List<org.hswebframework.ezorm.core.param.Term> terms){
        return terms.stream().flatMap(term -> java.util.stream.Stream.concat(java.util.stream.Stream.of(term),flatten(term.getTerms())));
    }
    @Test void roleLookupPreservesDispatcherScopeAndRechecksEachMember(){
        prepare();users.put("manager",user("manager",Set.of()));users.put("worker",user("worker",Set.of("query","review"),"reviewer"));
        users.put("outsider",user("outsider",Set.of("query","review"),"other"));
        var service=new DecorationNotificationRecipients(directory,resolver){
            @Override protected Mono<Authentication> authentication(String id){return Mono.justOrEmpty(users.get(id));}
            @Override protected Flux<UserInfo> executeMembers(QueryListCommand<UserInfo> command){
                return Authentication.currentReactive().flatMapMany(auth->{
                    assertEquals("manager",auth.getUser().getId());
                    var terms=flatten(command.asQueryParam().getTerms()).toList();
                    assertTrue(terms.stream().anyMatch(term -> List.of("reviewer").equals(term.getValue())));
                    assertTrue(terms.stream().anyMatch(term -> List.of("app").equals(term.getValue())));
                    assertTrue(terms.stream().anyMatch(term -> "status".equals(term.getColumn()) && Integer.valueOf(1).equals(term.getValue())));
                    assertTrue(terms.stream().anyMatch(term -> "manager".equals(term.getValue())));
                    return Flux.just("worker","outsider","missing","worker").map(id->{var u=new UserInfo();u.setId(id);return u;});
                });
            }
        };
        var registration=org.jetlinks.pro.assets.AssetsHolderHolder.registerProvider(auth -> Mono.just(
            new org.jetlinks.pro.assets.impl.CreatorAssetsHolder(auth.getUser(),mock(org.jetlinks.pro.assets.AssetManager.class))));
        try {StepVerifier.create(service.roleMembers(notice("reviewer"))).expectNext("worker").verifyComplete();}
        finally {registration.dispose();}
    }
}
