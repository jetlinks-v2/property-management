package org.jetlinks.pro.property.quality.service;

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

/** 品质只依赖基础模块公共契约，标注及空间绑定每次从同一个服务读取，不保存第二份镜像。 */
@Service @RequiredArgsConstructor
public class QualityDirectory {

    public Flux<PropertyOrganizationInfo> projects() {
        return support().flatMapMany(s -> s.execute(new QueryPropertyProjectsCommand()));
    }
    public Mono<PropertyOrganizationInfo> project(String id) {
        return projects().filter(p -> p.getId().equals(id)).next()
            .switchIfEmpty(Mono.error(new BusinessException("error.quality.project_not_enabled", 400)));
    }
    public Flux<PropertyUserInfo> employees() {
        var command = new QueryPropertyUsersCommand(); command.setEmployeesOnly(true);
        return support().flatMapMany(s -> s.execute(command));
    }
    /**
     * 先由认证公开命令读取实际应用成员，再按批次与调用者有权查看的在职员工取交集。
     * 成员关系只是必要条件，调用方仍逐人验证实时动作和项目/工单权限；不缓存权限快照。
     * 空成员列表不得发送空 IDs 目录命令（空 IDs 在基础契约中表示全量目录）。
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
            .switchIfEmpty(Mono.error(new BusinessException("error.quality.employee_required", 400)));
    }
    public Flux<PropertySpaceInfo> spaces(String projectId) {
        var command = new QueryPropertySpacesCommand(); command.setProjectId(projectId);
        return support().flatMapMany(s -> s.execute(command));
    }
    public Mono<PropertySpaceInfo> space(String projectId, String id) {
        return spaces(projectId).filter(p -> p.getId().equals(id)).next()
            .switchIfEmpty(Mono.error(new BusinessException("error.quality.space_project_mismatch", 400)));
    }
    private Mono<org.jetlinks.core.command.CommandSupport> support() {
        return support(QueryPropertyUsersCommand.SERVICE_ID);
    }
    protected Mono<org.jetlinks.core.command.CommandSupport> support(String serviceId) {
        return CommandSupportManagerProviders.getCommandSupport(serviceId)
            .switchIfEmpty(Mono.error(new BusinessException("error.quality.directory_unavailable", 503)));
    }

}
