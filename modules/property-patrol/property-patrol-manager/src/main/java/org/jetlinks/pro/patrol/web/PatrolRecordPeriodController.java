package org.jetlinks.pro.patrol.web;

import jakarta.annotation.Nonnull;

import lombok.AllArgsConstructor;

import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.crud.service.ReactiveCrudService;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.crud.CorrelatesAssetsHolderQueryController;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolTaskEntity;
import org.jetlinks.pro.patrol.service.PatrolTaskService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


import java.util.function.Function;

/** 记录详情的应巡时段；与记录查询共用功能权限和计划资产范围，不开放生成操作。 */
@RestController
@AllArgsConstructor
@Validated
@RequestMapping("/park/patrol/record-periods")
@Resource(id = "patrol-execution", name = "巡更执行记录")
public class PatrolRecordPeriodController
        implements CorrelatesAssetsHolderQueryController<PatrolTaskEntity, String> {
    private final PatrolTaskService service;
    

    @Nonnull
    public AssetType getAssetType() {
        return PatrolAssetType.patrolPlan;
    }

    @Nonnull
    public Function<PatrolTaskEntity, ?> getAssetIdMapper() {
        return PatrolTaskEntity::getPlanId;
    }

    @Nonnull
    public String getAssetProperty() {
        return "planId";
    }

    public ReactiveCrudService<PatrolTaskEntity, String> getService() {
        return service;
    }

}
