package org.jetlinks.pro.patrol.web;

import jakarta.annotation.Nonnull;

import lombok.AllArgsConstructor;

import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.crud.service.ReactiveCrudService;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.crud.CorrelatesAssetsHolderQueryController;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolGenerationEntity;
import org.jetlinks.pro.patrol.service.PatrolTaskGroupService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


import java.util.function.Function;

/** PC 日任务组只读查询；沿计划资产校验，知道任务组 ID 不代表拥有访问权限。 */
@RestController
@AllArgsConstructor
@Validated
@RequestMapping("/park/patrol/task-groups")
@Resource(id = "patrol-execution", name = "巡更执行记录")
public class PatrolTaskGroupController
        implements CorrelatesAssetsHolderQueryController<PatrolGenerationEntity, String> {
    private final PatrolTaskGroupService service;


    @Nonnull
    public AssetType getAssetType() {
        return PatrolAssetType.patrolPlan;
    }

    @Nonnull
    public Function<PatrolGenerationEntity, ?> getAssetIdMapper() {
        return PatrolGenerationEntity::getPlanId;
    }

    @Nonnull
    public String getAssetProperty() {
        return "planId";
    }

    public ReactiveCrudService<PatrolGenerationEntity, String> getService() {
        return service;
    }

}
