package org.jetlinks.pro.property.quality.service;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.id.IDGenerator;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.assets.scope.AssetScopeContext;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.jetlinks.pro.tenant.assets.TenantAssetsHolder;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.jetlinks.pro.property.quality.entity.QualityEntity;
import reactor.core.publisher.Mono;
import java.util.List;
/** 品质所有独立入口的授权 owner；统一与请求租户、当前应用及 AssetsHolder 取交集。 */
@Component @RequiredArgsConstructor
public class QualityScope {
 private final AssetManager manager;
 public Mono<QualityContext> current(){
  return Mono.zip(TenantHolder.currentId(),AssetScopeContext.currentReactive().flatMap(s->Mono.justOrEmpty(s.getScope(BusinessApplicationDimension.TYPE))),Authentication.currentReactive())
   .map(t->new QualityContext(t.getT1(),t.getT2().id(),t.getT3().getUser().getId(),t.getT3().getUser().getName()))
   .switchIfEmpty(Mono.error(new AccessDenyException()))
   .flatMap(c->tenant(c).assertPermission0(AssetType.of("business_application"),List.of(c.applicationId()),CrudAssetPermission.read).thenReturn(c));
 }
 private TenantAssetsHolder tenant(QualityContext c){return new TenantAssetsHolder(List.of(c.tenantId()),manager);}
 public Mono<QueryParamEntity> query(QueryParamEntity query,AssetType type,String property){
  return current().flatMap(c->{
   var param=query.clone().toNestQuery().and("applicationId",c.applicationId()).and("deleted",false).getParam();
   if(param.getSorts().isEmpty())param.toQuery().orderByDesc("createTime").orderByDesc("id");
   param.setPageSize(Math.min(Math.max(param.getPageSize(),1),200));
   return AssetsHolder.injectQueryParam(param,type,property).flatMap(q->tenant(c).injectQuery(q,type.getId(),property,CrudAssetPermission.read));
  });
 }
 public Mono<Void> assertRecord(QualityEntity row,AssetType type,String assetId,CrudAssetPermission permission){
  return current().flatMap(c->{
   if(Boolean.TRUE.equals(row.getDeleted())||!c.applicationId().equals(row.getApplicationId())||!c.tenantId().equals(row.getTenantId()))return Mono.error(new AccessDenyException());
   return tenant(c).assertPermission0(type,List.of(assetId),CrudAssetPermission.read).then(AssetsHolder.assertPermission(type,assetId,permission));
  });
 }
 public static <T extends QualityEntity>T initialize(T row,QualityContext c){
  row.setId(IDGenerator.MD5.generate());row.setApplicationId(c.applicationId());row.setTenantId(c.tenantId());
  row.setCreatorId(c.userId());row.setCreatorName(c.userName());row.setCreateTime(System.currentTimeMillis());row.setModifyTime(row.getCreateTime());row.setVersion(0L);row.setDeleted(false);return row;
 }
 public static void inherit(QualityEntity row,QualityEntity parent){
  row.setApplicationId(parent.getApplicationId());row.setTenantId(parent.getTenantId());row.setProjectId(parent.getProjectId());row.setProjectName(parent.getProjectName());row.setOrganizationId(parent.getOrganizationId());
 }
 public static void require(boolean valid,String code){if(!valid)throw new org.hswebframework.web.exception.BusinessException("error.quality."+code,code.equals("conflict")?409:400);}
}
