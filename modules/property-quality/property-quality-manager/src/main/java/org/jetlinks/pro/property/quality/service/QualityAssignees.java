package org.jetlinks.pro.property.quality.service;
import lombok.RequiredArgsConstructor;import org.springframework.stereotype.Service;
import org.hswebframework.web.authorization.*;import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.assets.*;import org.jetlinks.pro.assets.crud.CrudAssetPermission;import org.jetlinks.pro.assets.scope.*;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;import org.jetlinks.pro.property.base.user.PropertyUserInfo;
import org.jetlinks.pro.property.quality.asset.QualityAssetType;import org.jetlinks.pro.property.quality.entity.QualityWorkOrderEntity;
import reactor.core.publisher.*;import java.util.*;
/** 以候选员工自己的实时应用角色和目标工单保存权限判断资格，不信任前端传入姓名或角色。 */
@Service @RequiredArgsConstructor public class QualityAssignees {
 private final QualityDirectory directory;private final AssetScopeResolverManager resolver;
 public Flux<PropertyUserInfo> candidates(QualityWorkOrderEntity order,String action,String role){return QualityCandidateScan.filter(directory.employees(),p->eligible(p,order,action,role));}
 public Mono<PropertyUserInfo> assertEligible(String id,QualityWorkOrderEntity order,String action,String role){return directory.employee(id).filterWhen(p->eligible(p,order,action,role)).switchIfEmpty(Mono.error(new AccessDenyException()));}
 private Mono<Boolean> eligible(PropertyUserInfo person,QualityWorkOrderEntity order,String action,String role){
  return ReactiveAuthenticationHolder.get(person.getId()).flatMap(auth->resolver.resolve(AssetScope.of(BusinessApplicationDimension.TYPE,order.getApplicationId()),auth))
   .filter(a->a.authentication().hasPermission("quality-work-order",action))
   .filter(a->role==null||role.isBlank()||a.authentication().getDimensions("role").stream().anyMatch(r->r.getId().equals(role)))
   .flatMap(a->AssetsHolder.fromAuth(a.authentication(),a.context())).flatMap(h->h.hasPermission(QualityAssetType.qualityWorkOrder,List.of(order.getId()),CrudAssetPermission.save))
   .defaultIfEmpty(false).onErrorReturn(AccessDenyException.class,false);
 }
}
