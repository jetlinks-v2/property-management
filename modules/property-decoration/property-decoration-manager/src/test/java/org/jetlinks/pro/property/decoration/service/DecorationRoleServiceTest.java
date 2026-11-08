package org.jetlinks.pro.property.decoration.service;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.core.command.Command;
import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.core.metadata.FunctionMetadata;
import org.jetlinks.pro.command.CommandSupportManagerProvider;
import org.jetlinks.sdk.server.auth.RoleInfo;
import org.jetlinks.sdk.server.commons.cmd.QueryPagerCommand;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DecorationRoleServiceTest {
    private DecorationScope scope(){
        var scope=mock(DecorationScope.class);
        when(scope.current()).thenReturn(Mono.just(new DecorationScope.Context("tenant","app","admin","管理人",null)));
        return scope;
    }
    @Test void queryPreservesCallerContextAndNestsUntrustedOrBeforeApplication(){
        var input=QueryParamEntity.newQuery().where("name","管理人").or("applicationId","other").or("state","disabled").getParam();input.setPageSize(999);input.setPageIndex(1);
        var role=new RoleInfo();role.setId("role");role.setName("角色");role.setExtension("applicationId","app");
        var service=new DecorationRoleService(scope()){
            @Override protected Mono<PagerResult<RoleInfo>> execute(QueryPagerCommand<RoleInfo> command){
                var q=command.asQueryParam();assertEquals(200,q.getPageSize());
                assertTrue(q.getTerms().stream().anyMatch(t->"applicationId".equals(t.getColumn())&&"app".equals(t.getValue())));
                assertTrue(q.getTerms().stream().anyMatch(t->"state".equals(t.getColumn())&&"enabled".equals(t.getValue())));
                assertTrue(q.getTerms().stream().anyMatch(t->!t.getTerms().isEmpty()));
                return Mono.deferContextual(ctx->{assertEquals("caller",ctx.get("marker"));var actual=q.clone();actual.setPageIndex(0);return Mono.just(PagerResult.of(1,List.of(role),actual));});
            }
        };
        StepVerifier.create(service.query(input).contextWrite(c->c.put("marker","caller")))
            .assertNext(page->{assertEquals(0,page.getPageIndex());assertEquals(new DecorationRoleService.Role("role","角色"),page.getData().get(0));}).verifyComplete();
        assertEquals(999,input.getPageSize());
    }
    @Test @SuppressWarnings({"unchecked","rawtypes"}) void rpcProjectionRejectsForeignApplicationAndMissingRole(){
        for(var app:List.of("app","other")){
            var service=new DecorationRoleService(scope()){
                @Override protected Mono<PagerResult<RoleInfo>> execute(QueryPagerCommand<RoleInfo> command){
                    return Mono.just((PagerResult)PagerResult.of(1,List.of(Map.of("id","role","name","角色","applicationId",app)),command.asQueryParam()));
                }
            };
            if(app.equals("app")){
                StepVerifier.create(service.require("role")).expectNext(new DecorationRoleService.Role("role","角色")).verifyComplete();
                StepVerifier.create(service.require("hidden")).expectErrorSatisfies(e->assertEquals(403,((BusinessException)e).getStatus())).verify();
            }else StepVerifier.create(service.query(new QueryParamEntity())).expectError(AccessDenyException.class).verify();
        }
    }
    @Test void missingCommandIsUnavailableAndIsNotExecutedAsAnEmptyDirectory(){
        var previous=CommandSupportManagerProvider.supports.get("authService");
        var registration=CommandSupportManagerProvider.supports.register("authService",new CommandSupportManagerProvider(){
            public String getProvider(){return "authService";}
            public Mono<? extends CommandSupport> getCommandSupport(String module,Map<String,Object> options){return Mono.just(new CommandSupport(){
                public <R> R execute(Command<R> command){throw new AssertionError("missing command must not execute");}
                public Mono<FunctionMetadata> getCommandMetadata(String commandId){return Mono.empty();}
            });}
        });
        try{StepVerifier.create(new DecorationRoleService(scope()).query(new QueryParamEntity()))
            .expectErrorSatisfies(e->assertEquals(503,((BusinessException)e).getStatus())).verify();}
        finally{registration.dispose();previous.ifPresent(p->CommandSupportManagerProvider.supports.register("authService",p));}
    }
}
