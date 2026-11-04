package org.jetlinks.pro.property.quality.service;
import lombok.RequiredArgsConstructor;import org.springframework.stereotype.Service;import org.hswebframework.web.exception.NotFoundException;
import org.jetlinks.pro.command.CommandSupportManagerProviders;import org.jetlinks.pro.smartpark.repair.api.*;
import org.jetlinks.pro.property.quality.api.QualityTransferRequest;import org.jetlinks.pro.property.quality.entity.*;import reactor.core.publisher.*;import java.util.*;
import static org.jetlinks.pro.property.quality.service.QualityScope.require;
/** 品质侧只消费报修 core 命令，业务结果写回可重试；远端成功与本地写回不伪装成分布式事务。 */
@Service @RequiredArgsConstructor public class QualityRepairBridge {
 private final QualityRecordService records;private final QualityWorkOrders access;private final QualityConfigurationService configs;private final QualityScope scope;
 private Mono<String> target(){return scope.current().flatMap(c->configs.createQuery().where("applicationId",c.applicationId()).and("kind","settings").and("deleted",false).fetchOne()).map(r->QualityConfigurationRules.text(r.getData(),"repairApplicationId")).filter(s->!s.isBlank()).switchIfEmpty(Mono.error(new org.hswebframework.web.exception.BusinessException("error.quality.repair_not_configured",400)));}
 public Flux<QualityRepairOption> options(String project){return target().flatMapMany(app->{var command=new QueryQualityRepairOptionsCommand();command.setTargetApplicationId(app);command.setProjectId(project);return support().flatMapMany(s->s.execute(command)).onErrorMap(QualityRepairBridge::mapUnavailable);});}
 public Mono<QualityRepairInfo> transfer(String recordId,QualityTransferRequest request){return records.findById(recordId).switchIfEmpty(Mono.error(new NotFoundException())).flatMap(row->access.writable(row.getWorkOrderId()).thenReturn(row)).flatMap(row->{
  require(Objects.equals(row.getIsQualified(),0),"qualified_cannot_transfer");require(Objects.equals(row.getProjectId(),request.getRequest().getBusinessProjectId()),"reference_scope");
  request.getRequest().setAttachmentIds(row.getPhotoIds()==null?List.of():row.getPhotoIds());
  return target().flatMap(app->{var command=new CreateQualityRepairCommand();command.setTargetApplicationId(app);command.setSourceRecordId(recordId);command.setRequest(request.getRequest());return support().flatMap(s->s.execute(command)).onErrorMap(QualityRepairBridge::mapUnavailable).flatMap(info->records.createUpdate().set("repairApplicationId",app).set("repairOrderId",info.id()).where("id",recordId).execute().thenReturn(info));});
 });}
 public Mono<QualityRepairInfo> linked(String recordId){return records.findById(recordId).switchIfEmpty(Mono.error(new NotFoundException())).flatMap(row->access.read(row.getWorkOrderId()).thenReturn(row)).flatMap(row->{var command=new GetQualityRepairCommand();command.setTargetApplicationId(row.getRepairApplicationId());command.setSourceRecordId(recordId);return support().flatMap(s->s.execute(command)).onErrorMap(QualityRepairBridge::mapUnavailable);});}
 Mono<org.jetlinks.core.command.CommandSupport> support(){
  // 使用框架解析 provider:service；只把缺少提供者/服务映射为503，权限及业务错误原样传播。
  // 每次订阅重新发现，保证报修重启恢复后可直接重试。
  return org.jetlinks.pro.command.CommandSupportUtils.resolve(CreateQualityRepairCommand.SERVICE_ID,(providerId,serviceId)->
   Mono.defer(()->Mono.justOrEmpty(CommandSupportManagerProviders.getProvider(providerId)))
    .flatMap(provider->provider.getCommandSupport(serviceId,Map.of()).cast(org.jetlinks.core.command.CommandSupport.class))
    .switchIfEmpty(Mono.error(new org.hswebframework.web.exception.BusinessException("error.quality.repair_unavailable",503))));
 }
 // 集群提供者可在实例离线后继续存在，真正选路发生在 execute；仅转换框架明确的服务缺失错误。
 static Throwable mapUnavailable(Throwable error){
  if(error instanceof org.hswebframework.web.exception.I18nSupportException i18n
    && Set.of("error.unsupported_command_service","error.command.server.not.found").contains(i18n.getI18nCode()))
   return new org.hswebframework.web.exception.BusinessException("error.quality.repair_unavailable",error,503);
  return error;
 }

}
