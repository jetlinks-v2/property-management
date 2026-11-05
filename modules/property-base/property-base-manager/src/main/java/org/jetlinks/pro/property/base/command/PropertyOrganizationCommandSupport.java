package org.jetlinks.pro.property.base.command;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.jetlinks.core.annotation.command.CommandHandler;
import org.jetlinks.pro.annotation.command.CommandService;
import org.jetlinks.pro.property.base.entity.PropertyOrganizationEntity;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.property.base.organization.OrganizationType;
import org.jetlinks.pro.property.base.service.PropertyOrganizationService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 基础模块的只读组织命令入口，与 HTTP 查询复用认证、资产过滤及逻辑删除规则。
 * 消费模块校验自己的菜单权限；此入口不要求组织类型维护菜单，也不提升为同步器的系统身份。
 *
 * @see QueryPropertyOrganizationTreeCommand
 * @since 2.20.0
 */
@Component
@Authorize
@RequiredArgsConstructor
@CommandService(
        id = QueryPropertyOrganizationTreeCommand.SERVICE_ID,
        name = "物业组织服务",
        description = "供物业业务模块查询已标注类型的完整组织树")
public class PropertyOrganizationCommandSupport {
    private final PropertyOrganizationService organizations;
    private final org.springframework.beans.factory.ObjectProvider<org.jetlinks.pro.property.base.service.PropertyProjectSealService> seals;

    /** 章与组织树共用组织公开服务，仍由基础服务校验项目类型和read资产权限。 */
    @CommandHandler(name = "获取项目章")
    public reactor.core.publisher.Mono<org.jetlinks.pro.property.base.organization.PropertyProjectSeal> projectSeal(GetPropertyProjectSealCommand command) {
        return seals.getObject().read(command.getProjectId());
    }


    /** 沿用订阅者的权限上下文，逐根节点返回公共 DTO，不暴露内部 Entity。 */
    @CommandHandler(name = "查询物业组织树", description = "返回有权访问的完整树及当前类型标注")
    public Flux<PropertyOrganizationInfo> queryTree(QueryPropertyOrganizationTreeCommand command) {
        return organizations.tree().flatMapMany(Flux::fromIterable).map(this::toInfo);
    }

    private PropertyOrganizationInfo toInfo(PropertyOrganizationEntity entity) {
        var info = new PropertyOrganizationInfo();
        info.setId(entity.getId());
        info.setName(entity.getName());
        info.setCode(entity.getCode());
        info.setParentId(entity.getParentId());
        info.setSortIndex(entity.getSortIndex());
        info.setOrganizationType(entity.getOrganizationType());
        info.setProjectSealFileId(entity.getOrganizationType() == OrganizationType.project
                ? entity.getProjectSealFileId() : null);
        info.setChildren(entity.getChildren().stream().map(this::toInfo).toList());
        return info;
    }
}
