package org.jetlinks.pro.smartpark.repair.service;
import lombok.RequiredArgsConstructor;import org.springframework.stereotype.Component;
import org.hswebframework.web.authorization.*;import org.hswebframework.web.authorization.annotation.Authorize;import org.hswebframework.web.authorization.exception.AccessDenyException;import org.hswebframework.web.exception.NotFoundException;
import org.jetlinks.core.annotation.command.CommandHandler;import org.jetlinks.pro.annotation.command.CommandService;
import org.jetlinks.pro.assets.*;import org.jetlinks.pro.assets.scope.*;import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.jetlinks.pro.smartpark.repair.api.*;import org.jetlinks.pro.smartpark.repair.entity.QualityRepairLinkEntity;
import org.jetlinks.pro.property.service.TenantAssetScope;import org.jetlinks.pro.tenant.holder.TenantHolder;
import reactor.core.publisher.*;import java.util.*;import java.nio.charset.StandardCharsets;import java.util.function.Supplier;
/** 品质转报修公开边界：先确认来源动作，再用同一真实用户激活目标应用；不继承来源角色或提升为系统身份。 */
@Component @Authorize @RequiredArgsConstructor @CommandService(id=CreateQualityRepairCommand.SERVICE_ID,name="品质报修联动",description="读取报修表单配置、幂等创建及读取品质关联工单")
public class QualityRepairCommandSupport {
 private final AssetScopeResolverManager resolver;private final TenantAssetScope tenantScope;private final RepairOrderManager manager;
 private final RepairConfigurationService configurations;private final RepairConfigurationControllerAdapter adapter;
 private final QualityRepairLinkService links;private final QualityRepairTransaction transaction;
 @CommandHandler(name="品质报修发起配置") public Flux<QualityRepairOption> options(QueryQualityRepairOptionsCommand command){
  return withTarget(command.getTargetApplicationId(),"create",()->adapter.options(command.getProjectId()).collectList()).flatMapMany(Flux::fromIterable);
 }
 @CommandHandler(name="品质来源创建报修") public Mono<QualityRepairInfo> create(CreateQualityRepairCommand command){
  return origin(command.getSourceRecordId()).flatMap(link->{link.setTargetApplicationId(command.getTargetApplicationId());
   return withTarget(command.getTargetApplicationId(),"create",()->links.findById(link.getId()).flatMap(existing->read(existing,command.getTargetApplicationId()))
    .switchIfEmpty(Mono.defer(()->transaction.create(link,command.getRequest())).onErrorResume(org.springframework.dao.DuplicateKeyException.class,e->links.findById(link.getId()).flatMap(existing->read(existing,command.getTargetApplicationId())))));
  });
 }
 @CommandHandler(name="品质关联报修摘要") public Mono<QualityRepairInfo> get(GetQualityRepairCommand command){return origin(command.getSourceRecordId()).flatMap(source->withTarget(command.getTargetApplicationId(),"query",()->links.findById(source.getId()).switchIfEmpty(Mono.error(new NotFoundException())).flatMap(link->read(link,command.getTargetApplicationId()))));}
 private Mono<QualityRepairInfo> read(QualityRepairLinkEntity link,String target){
  RepairFlowRules.require(target.equals(link.getTargetApplicationId()),"request_conflict");return manager.read(link.getOrderId()).map(o->new QualityRepairInfo(o.getId(),o.getNumber(),o.getStatus(),o.getApplicationId()));
 }
 private Mono<QualityRepairLinkEntity> origin(String record){return Mono.zip(TenantHolder.currentId(),AssetScopeContext.currentReactive().flatMap(s->Mono.justOrEmpty(s.getScope(BusinessApplicationDimension.TYPE)))).map(t->{var link=new QualityRepairLinkEntity();link.setTenantId(t.getT1());link.setSourceApplicationId(t.getT2().id());link.setSourceRecordId(record);link.setId(UUID.nameUUIDFromBytes((t.getT1()+":"+t.getT2().id()+":"+record).getBytes(StandardCharsets.UTF_8)).toString().replace("-",""));return link;});}
 private <T> Mono<T> withTarget(String target,String action,Supplier<Mono<T>> work){return Authentication.currentReactive().filter(auth->auth.hasPermission("quality-work-order","transferRepair"))
  .flatMap(auth->tenantScope.assertOwned(AssetType.of("business_application"),target)
   // 来源认证已裁剪到品质应用；重新加载同一用户的实时授权，交给目标 scope 重新裁剪。
   .then(ReactiveAuthenticationHolder.get(auth.getUser().getId()))
   .flatMap(current->activateTarget(resolver,current,target)))
  .filter(active->active.authentication().hasPermission("repair-order",action)).switchIfEmpty(Mono.error(new AccessDenyException()))
  .flatMap(active->AssetsHolder.fromAuth(active.authentication(),active.context()).flatMap(holder->Mono.defer(work)
   .contextWrite(c->c.put(Authentication.class,active.authentication()).put(AssetsHolder.class,holder)).contextWrite(AssetScopeContext.replace(active.context()))));}

 /** 显式跨应用调用替换应用 scope 后重新激活，保留其他 scope；直接 merge 会把两个应用判为冲突。 */
 static Mono<AssetScopeActivation> activateTarget(AssetScopeResolverManager resolver,Authentication authentication,String target){
  // resolver 负责 scope 激活和权限裁剪，不负责证明用户属于目标应用。
  if(authentication.getDimension(BusinessApplicationDimension.INSTANCE,target).isEmpty())return Mono.error(new AccessDenyException());
  var destination=AssetScope.of(BusinessApplicationDimension.TYPE,target);
  return AssetScopeContext.currentReactive().flatMap(source->{
   var scopes=new ArrayList<>(source.getScopes().stream().filter(s->!BusinessApplicationDimension.TYPE.equals(s.type())).toList());
   scopes.add(destination);
   return resolver.resolve(destination,authentication).contextWrite(AssetScopeContext.replace(AssetScopeContext.of(scopes)));
  });
 }
}
