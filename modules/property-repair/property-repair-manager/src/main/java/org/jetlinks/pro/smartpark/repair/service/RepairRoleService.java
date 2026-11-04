package org.jetlinks.pro.smartpark.repair.service;

import lombok.AllArgsConstructor;
import org.hswebframework.web.authorization.ReactiveAuthenticationHolder;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.assets.scope.AssetScope;
import org.jetlinks.pro.assets.scope.AssetScopeResolverManager;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.jetlinks.pro.property.base.user.PropertyUserInfo;
import org.jetlinks.pro.smartpark.repair.asset.RepairAssetType;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** 指定业务角色使用实时应用认证及工单授权，不能依赖上次同步的角色列表。 */
@Service @AllArgsConstructor
public class RepairRoleService {
    private final AssetScopeResolverManager resolver;
    private final RepairDirectory people;

    public Mono<Boolean> eligible(String userId,String roleId,String action,RepairOrderEntity order,RepairContext context){
        if(roleId==null||roleId.isBlank())return Mono.just(false);
        return permitted(userId,roleId,action,order,context);
    }
    /** 无指定角色的通知对象仍须具备当前应用动作与目标工单资产权限。 */
    public Mono<Boolean> permitted(String userId,String action,RepairOrderEntity order,RepairContext context){
        return permitted(userId,null,action,order,context);
    }
    private Mono<Boolean> permitted(String userId,String roleId,String action,RepairOrderEntity order,RepairContext context){
        return people.withUser(userId, context, () -> people.user(userId)
            .filter(person -> person.isEmployee() && person.getStatus() != null && person.getStatus() == 1)
            .flatMap(person -> org.hswebframework.web.authorization.Authentication.currentReactive())
            .filter(auth -> auth.hasPermission("repair-order", action)
                && (roleId == null || auth.getDimensions("role").stream().anyMatch(role -> roleId.equals(role.getId()))))
            .flatMap(auth -> AssetsHolder.currentReactive())
            .flatMap(holder -> holder.hasPermission(RepairAssetType.repairOrder, java.util.List.of(order.getId()),
                "query".equals(action) ? CrudAssetPermission.read : CrudAssetPermission.save)))
            .defaultIfEmpty(false).onErrorReturn(AccessDenyException.class, false);
    }

    public Mono<Void> assertRole(String roleId,String action,RepairOrderEntity order,RepairContext context){
        return eligible(context.userId(),roleId,action,order,context)
            .flatMap(allowed->allowed?Mono.empty():Mono.error(new AccessDenyException()));
    }
    public Mono<Void> assertAvailable(String roleId,String action,RepairOrderEntity order,RepairContext context){
        RepairFlowRules.require(roleId!=null&&!roleId.isBlank(),"review_role_required");
        return people.employeesFor(order)
            .filterWhen(person->eligible(person.getId(),roleId,action,order,context),8).hasElements()
            .doOnNext(exists->RepairFlowRules.require(exists,"role_has_no_eligible_users")).then();
    }
}
