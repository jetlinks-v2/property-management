package org.jetlinks.pro.patrol.web;

import jakarta.annotation.Nonnull;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;

import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.ResourceAction;
import org.hswebframework.web.crud.service.ReactiveCrudService;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.crud.CorrelatesAssetsHolderQueryController;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolTaskEntity;
import org.jetlinks.pro.patrol.service.PatrolTaskGenerator;
import org.jetlinks.pro.patrol.service.PatrolTaskService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.util.function.Function;

/** 任务只读查询；业务写操作另有状态机，不开放通用 CRUD。 */
@RestController
@AllArgsConstructor
@Validated
@RequestMapping("/park/patrol/tasks")
@Resource(id = "patrol-task", name = "巡更任务")
public class PatrolTaskController
        implements CorrelatesAssetsHolderQueryController<PatrolTaskEntity, String> {
    private final PatrolTaskService service;
    private final PatrolTaskGenerator generator;

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

    public record PreviewRequest(@NotBlank String planId, @NotNull LocalDate date) {}

    /** planId 省略时，按当前应用及计划资产权限批量生成指定日期。 */
    public record GenerateRequest(String planId, @NotNull LocalDate date) {}

    @PostMapping("/_preview")
    @QueryAction
    public Flux<PatrolTaskEntity> preview(@RequestBody @Valid PreviewRequest request) {
        return generator.preview(request.planId(), request.date());
    }

    @PostMapping("/_generate")
    @ResourceAction(id = "generate", name = "生成巡更任务")
    public Flux<PatrolTaskEntity> generate(@RequestBody @Valid GenerateRequest request) {
        if (request.planId() == null || request.planId().isBlank()) {
            return generator.generate(request.date());
        }
        return generator.generate(request.planId(), request.date());
    }
}
