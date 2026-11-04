package org.jetlinks.pro.patrol.web;

import jakarta.annotation.Nonnull;

import lombok.AllArgsConstructor;

import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.exception.BusinessException;
import org.hswebframework.web.crud.service.ReactiveCrudService;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.crud.CorrelatesAssetsHolderQueryController;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolGenerationEntity;
import org.jetlinks.pro.patrol.service.PatrolTaskGroupService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;


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
    static final int EXPORT_LIMIT = 10000;

    /** 全量导出沿用列表的筛选与计划资产权限；超限明确失败，不静默丢失尾部数据。 */
    @PostMapping("/export")
    @QueryAction
    public Mono<ResponseEntity<byte[]>> export(@RequestBody QueryParamEntity query) {
        query.setPageIndex(0);
        query.setPageSize(EXPORT_LIMIT + 1);
        query.setPaging(true);
        return injectQueryParam(query)
                .flatMap(service::queryPager)
                .flatMap(page -> {
                    if (page.getTotal() > EXPORT_LIMIT) {
                        return Mono.error(new BusinessException("error.patrol_record_export_limit"));
                    }
                    return Mono.fromCallable(() -> PatrolTaskGroupExport.write(page.getData()))
                            .subscribeOn(Schedulers.boundedElastic());
                })
                .map(bytes -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=patrol-records.xlsx")
                        .body(bytes));
    }

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

    /** 无分页查询和分页查询使用同一快照投影，并保留计划资产权限注入。 */
    @Override
    public reactor.core.publisher.Flux<PatrolGenerationEntity> query(
            org.hswebframework.web.api.crud.entity.QueryParamEntity query) {
        return injectQueryParam(query).flatMapMany(service::query);
    }

    public ReactiveCrudService<PatrolGenerationEntity, String> getService() {
        return service;
    }

}
