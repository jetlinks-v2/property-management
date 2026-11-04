package org.jetlinks.pro.smartpark.repair.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.id.IDGenerator;
import org.hswebframework.web.logging.AccessLoggerInfo;
import org.hswebframework.web.logging.events.AccessLoggerBeforeEvent;
import org.hswebframework.web.logging.events.AccessLoggerAfterEvent;
import org.jetlinks.pro.smartpark.repair.entity.RepairConfigurationEntity;
import org.jetlinks.pro.smartpark.repair.entity.RepairOperationLogEntity;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.util.Map;
import java.util.Objects;

/** 复用框架访问日志事件，在调用前固定可信归属，调用后记录成功及失败。 */
@Component @AllArgsConstructor @Slf4j
public class RepairOperationLogListener {
    static final String TENANT="repairAuditTenant",APPLICATION="repairAuditApplication";
    static final String USER="repairAuditUser",NAME="repairAuditName";
    private final RepairContextProvider contexts;
    private final RepairConfigurationService configurations;
    private final RepairOperationLogService logs;

    @EventListener
    public void before(AccessLoggerBeforeEvent event) {
        AccessLoggerInfo info=event.getLogger();
        if(!supported(info))return;
        Mono<RepairContext> identity=info.getTarget()==org.jetlinks.pro.smartpark.repair.web.RepairPublicController.class
            ? publicIdentity(info) : contexts.current().onErrorResume(AccessDenyException.class,error->Mono.empty());
        event.async(identity.doOnNext(context->stamp(info,context)).then()
            .onErrorResume(error->{log.warn("Unable to resolve repair audit scope: {}",error.getClass().getSimpleName());return Mono.empty();}));
    }

    @EventListener
    public void after(AccessLoggerAfterEvent event) {
        AccessLoggerInfo info=event.getLogger();
        if(!supported(info)||info.getContext()==null||!info.getContext().containsKey(APPLICATION))return;
        // 审计是访问日志副作用；失败请求在业务事务结束后仍能留下记录，审计失败不改写原响应。
        event.async(logs.insert(project(info)).then().onErrorResume(error->{
            log.warn("Unable to persist repair audit: {}",error.getClass().getSimpleName());return Mono.empty();
        }));
    }

    private Mono<RepairContext> publicIdentity(AccessLoggerInfo info) {
        String code=Objects.toString(info.getParameters().get("code"),"");
        return configurations.createQuery().where(RepairConfigurationEntity::getQrCode,code)
            .and(RepairConfigurationEntity::getKind,"qr").fetchOne()
            .map(row->new RepairContext(Objects.toString(row.getExtension("tenantId"),""),
                row.getApplicationId(),"anonymous-qr:"+row.getId(),"二维码匿名报事"));
    }

    static boolean supported(AccessLoggerInfo info) {
        if(info.getTarget()==null||info.getTarget()==org.jetlinks.pro.smartpark.repair.web.RepairOperationLogController.class)return false;
        String target=info.getTarget().getName();
        return target.startsWith("org.jetlinks.pro.smartpark.repair.web.")||target.startsWith("org.jetlinks.pro.property.web.");
    }

    static void stamp(AccessLoggerInfo info,RepairContext context) {
        info.putContext(TENANT,context.tenantId());info.putContext(APPLICATION,context.applicationId());
        info.putContext(USER,context.userId());info.putContext(NAME,context.userName());
    }

    static RepairOperationLogEntity project(AccessLoggerInfo info) {
        Map<String,String> context=info.getContext();
        RepairOperationLogEntity row=new RepairOperationLogEntity();row.setId(IDGenerator.MD5.generate());
        row.setTenantId(context.get(TENANT));row.setApplicationId(context.get(APPLICATION));
        row.setOperatorId(context.get(USER));row.setOperatorName(context.get(NAME));
        row.setOperation(shortText(info.getDescribe(),512));row.setAction(shortText(info.getAction(),128));
        row.setHttpMethod(shortText(info.getHttpMethod(),16));
        row.setUrl(shortText(Objects.toString(info.getUrl(),"").split("\\?",2)[0],1024));
        row.setStatus(info.getException()==null?0:1);
        if(info.getException()!=null)row.setErrorType(shortText(info.getException().getClass().getSimpleName(),128));
        row.setCreateTime(info.getRequestTime());row.setDuration(Math.max(0,info.getResponseTime()-info.getRequestTime()));
        return row;
    }
    private static String shortText(String value,int max){return value==null?null:value.substring(0,Math.min(max,value.length()));}
}
