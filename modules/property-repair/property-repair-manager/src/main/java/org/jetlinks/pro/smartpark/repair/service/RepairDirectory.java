package org.jetlinks.pro.smartpark.repair.service;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.ReactiveAuthenticationHolder;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.scope.AssetScope;
import org.jetlinks.pro.assets.scope.AssetScopeContext;
import org.jetlinks.pro.assets.scope.AssetScopeResolverManager;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.base.command.QueryPropertyProjectsCommand;
import org.jetlinks.pro.property.base.command.QueryPropertyUsersCommand;
import org.jetlinks.pro.property.base.command.QueryPropertySpacesCommand;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.property.base.user.PropertyUserInfo;
import org.jetlinks.pro.property.base.space.PropertySpaceInfo;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Service;
import org.jetlinks.sdk.server.SdkServices;
import org.jetlinks.sdk.server.auth.DimensionUserBindInfo;
import org.jetlinks.sdk.server.auth.cmd.GetDimensionUserBindCommand;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** 报修只依赖基础模块公共契约，标注及空间绑定每次从同一个服务读取，不保存第二份镜像。 */
@Service @RequiredArgsConstructor
public class RepairDirectory {
    private final AssetScopeResolverManager resolver;
    private final RepairConfigurationService configurations;

    public Flux<PropertyOrganizationInfo> projects() {
        return support().flatMapMany(s -> s.execute(new QueryPropertyProjectsCommand()));
    }
    public Mono<PropertyOrganizationInfo> project(String id) {
        return projects().filter(p -> p.getId().equals(id)).next()
            .switchIfEmpty(Mono.error(new BusinessException("error.repair.project_not_enabled", 400)));
    }
    public Flux<PropertyUserInfo> employees() {
        var command = new QueryPropertyUsersCommand(); command.setEmployeesOnly(true);
        return support().flatMapMany(s -> s.execute(command));
    }
    /**
     * 先读取真实应用成员，再与调用者有权查看的启用员工取交集；成员关系不替代动作及工单授权。
     * 空成员列表不得向目录发送空 IDs，因为基础契约将空 IDs 解释为全量查询。
     */
    public Flux<PropertyUserInfo> employees(String applicationId) {
        var command = new GetDimensionUserBindCommand()
            .withDimension(BusinessApplicationDimension.TYPE, applicationId);
        return support(SdkServices.authService).flatMapMany(s -> s.execute(command))
            .filter(binding -> BusinessApplicationDimension.TYPE.equals(binding.getType())
                && applicationId.equals(binding.getId()))
            .flatMapIterable(DimensionUserBindInfo::getUserId)
            .distinct().buffer(200).concatMap(ids -> {
                var employees = new QueryPropertyUsersCommand();
                employees.setEmployeesOnly(true); employees.setIds(ids);
                return support().flatMapMany(s -> s.execute(employees));
            });
    }
    public Mono<PropertyUserInfo> user(String id) {
        var command = new QueryPropertyUsersCommand(); command.setIds(List.of(id));
        return support().flatMapMany(s -> s.execute(command)).next();
    }
    public Mono<PropertyUserInfo> employee(String id) {
        return user(id).filter(p -> p.isEmployee() && p.getStatus() != null && p.getStatus() == 1)
            .switchIfEmpty(Mono.error(new BusinessException("error.repair.employee_required", 400)));
    }
    public Flux<PropertySpaceInfo> spaces(String projectId) {
        var command = new QueryPropertySpacesCommand(); command.setProjectId(projectId);
        return support().flatMapMany(s -> s.execute(command));
    }
    public Mono<PropertySpaceInfo> space(String projectId, String id) {
        return spaces(projectId).filter(p -> p.getId().equals(id)).next()
            .switchIfEmpty(Mono.error(new BusinessException("error.repair.space_project_mismatch", 400)));
    }
    private Mono<org.jetlinks.core.command.CommandSupport> support() {
        return support(QueryPropertyUsersCommand.SERVICE_ID);
    }
    protected Mono<org.jetlinks.core.command.CommandSupport> support(String serviceId) {
        return CommandSupportManagerProviders.getCommandSupport(serviceId)
            .switchIfEmpty(Mono.error(new BusinessException("error.repair.directory_unavailable", 503)));
    }

    /** 系统调度和匿名二维码只能恢复可信工单来源的实时应用身份，不能通过无认证查询扩大目录。 */
    public Flux<PropertyUserInfo> employeesFor(RepairOrderEntity order) {
        return Authentication.currentReactive().hasElement().flatMapMany(authenticated -> {
            if (authenticated) return employees(order.getApplicationId());
            Mono<String> publisher = order.getQrConfigurationId() == null
                ? Mono.justOrEmpty(order.getCreatorId())
                : configurations.findById(order.getQrConfigurationId())
                    .filter(qr -> Objects.equals(qr.getApplicationId(), order.getApplicationId())
                        && Objects.equals(qr.getExtension("tenantId"), order.getExtension("tenantId")))
                    .map(qr -> qr.getQrPublisherId());
            RepairContext context = new RepairContext(Objects.toString(order.getExtension("tenantId")),
                order.getApplicationId(), "system", "系统");
            return publisher.flatMap(id -> withUser(id, context, () -> employees(order.getApplicationId()).collectList()))
                .flatMapMany(Flux::fromIterable);
        });
    }

    /** 通知与角色资格检查以目标账号自身的实时身份查询，缺少应用授权明确拒绝。 */
    public <T> Mono<T> withUser(String id, RepairContext context, Supplier<Mono<T>> action) {
        return ReactiveAuthenticationHolder.get(id)
            .flatMap(auth -> resolver.resolve(AssetScope.of(BusinessApplicationDimension.TYPE, context.applicationId()), auth))
            .flatMap(active -> AssetsHolder.fromAuth(active.authentication(), active.context()).flatMap(holder ->
                Mono.defer(action).contextWrite(c -> c.put(Authentication.class, active.authentication()).put(AssetsHolder.class, holder))
                    .contextWrite(AssetScopeContext.replace(active.context()))))
            .switchIfEmpty(Mono.error(new AccessDenyException()))
            .contextWrite(TenantHolder.makeCurrent(context.tenantId()));
    }
}
