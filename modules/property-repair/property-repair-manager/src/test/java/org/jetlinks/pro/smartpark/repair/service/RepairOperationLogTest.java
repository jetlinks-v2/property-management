package org.jetlinks.pro.smartpark.repair.service;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.logging.AccessLoggerInfo;
import org.hswebframework.web.logging.events.AccessLoggerBeforeEvent;
import org.hswebframework.web.logging.events.AccessLoggerAfterEvent;
import org.jetlinks.pro.smartpark.repair.entity.RepairOperationLogEntity;
import org.jetlinks.pro.smartpark.repair.web.RepairConfigurationController;
import org.jetlinks.pro.smartpark.repair.web.RepairOperationLogController;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepairOperationLogTest {
    private static final RepairContext CTX=new RepairContext("tenant-a","app-a","admin-a","应用管理员");

    @Test void clientOrCannotExpandApplicationOrDisablePaging() {
        QueryParamEntity input=QueryParamEntity.newQuery().where("applicationId","foreign")
            .or("status",0).getParam();input.setPaging(false);input.setPageSize(200_000);
        QueryParamEntity scoped=RepairOperationLogService.scoped(CTX,input);
        assertTrue(scoped.isPaging());assertEquals(100,scoped.getPageSize());
        assertEquals(3,scoped.getTerms().size());assertEquals(2,scoped.getTerms().get(0).getTerms().size());
        assertEquals("tenant-a",scoped.getTerms().get(1).getValue());
        assertEquals("app-a",scoped.getTerms().get(2).getValue());
        assertEquals(2,input.getTerms().size(),"不能修改调用方原条件");
    }

    @Test void projectionKeepsScopeAndVisibleFieldsWithoutSecrets() {
        AccessLoggerInfo info=info();info.setParameters(Map.of("password","secret-body"));
        info.setHttpHeaders(Map.of("X-Access-Token","secret-token"));
        info.setUrl("/repair/configurations?password=secret-query");
        RepairOperationLogListener.stamp(info,CTX);
        RepairOperationLogEntity row=RepairOperationLogListener.project(info);
        assertEquals("tenant-a",row.getTenantId());assertEquals("app-a",row.getApplicationId());
        assertEquals("admin-a",row.getOperatorId());assertEquals("/repair/configurations",row.getUrl());
        assertEquals(0,row.getStatus());assertEquals(40L,row.getDuration());assertNull(row.getErrorType());
    }

    @Test void frameworkBeforeThenFailedAfterPersistsTrustedIdentity() {
        RepairContextProvider contexts=mock(RepairContextProvider.class);
        RepairOperationLogService logs=mock(RepairOperationLogService.class);
        when(contexts.current()).thenReturn(Mono.just(CTX));
        when(logs.insert(any(RepairOperationLogEntity.class))).thenReturn(Mono.just(1));
        RepairOperationLogListener listener=new RepairOperationLogListener(contexts,mock(RepairConfigurationService.class),logs);
        AccessLoggerInfo info=info();AccessLoggerBeforeEvent before=new AccessLoggerBeforeEvent(info);
        listener.before(before);StepVerifier.create(before.getAsync()).verifyComplete();
        info.setException(new IllegalArgumentException("secret payload"));
        AccessLoggerAfterEvent after=new AccessLoggerAfterEvent(info);listener.after(after);
        StepVerifier.create(after.getAsync()).verifyComplete();
        ArgumentCaptor<RepairOperationLogEntity> saved=ArgumentCaptor.forClass(RepairOperationLogEntity.class);
        verify(logs).insert(saved.capture());assertEquals(1,saved.getValue().getStatus());
        assertEquals("IllegalArgumentException",saved.getValue().getErrorType());assertEquals("app-a",saved.getValue().getApplicationId());
    }

    @Test void invalidContextDoesNotAssignClientIdentityAndLogReadIsNotRecursivelyCaptured() {
        RepairContextProvider contexts=mock(RepairContextProvider.class);
        when(contexts.current()).thenReturn(Mono.error(new AccessDenyException()));
        RepairOperationLogService logs=mock(RepairOperationLogService.class);
        RepairOperationLogListener listener=new RepairOperationLogListener(contexts,mock(RepairConfigurationService.class),logs);
        AccessLoggerInfo info=info();info.setHttpHeaders(Map.of("X-Tenant-Domain","foreign","X-Asset-Scope","business_application:foreign"));
        AccessLoggerBeforeEvent before=new AccessLoggerBeforeEvent(info);listener.before(before);
        StepVerifier.create(before.getAsync()).verifyComplete();listener.after(new AccessLoggerAfterEvent(info));
        verifyNoInteractions(logs);assertTrue(info.getContext()==null||!info.getContext().containsKey(RepairOperationLogListener.APPLICATION));
        info.setTarget(RepairOperationLogController.class);assertFalse(RepairOperationLogListener.supported(info));
    }

    private static AccessLoggerInfo info(){
        AccessLoggerInfo info=new AccessLoggerInfo();info.setTarget(RepairConfigurationController.class);
        info.setDescribe("报事配置");info.setAction("保存");info.setHttpMethod("POST");
        info.setUrl("/repair/configurations");info.setRequestTime(1000);info.setResponseTime(1040);return info;
    }
}
