package org.jetlinks.pro.patrol.web;

import jakarta.annotation.Nonnull;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;

import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.ResourceAction;
import org.hswebframework.web.crud.service.ReactiveCrudService;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.crud.CorrelatesAssetsHolderQueryController;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolExecutionEntity;
import org.jetlinks.pro.patrol.entity.PatrolExecutionEntity.Answer;
import org.jetlinks.pro.patrol.service.PatrolExecutionService;
import org.jetlinks.pro.patrol.service.PatrolExecutionWorkflow;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Function;

/** 执行记录只读查询；业务写操作另有状态机，不开放通用 CRUD。 */
@RestController
@AllArgsConstructor
@Validated
@RequestMapping("/park/patrol/executions")
@Resource(id = "patrol-execution", name = "巡更执行记录")
public class PatrolExecutionController
        implements CorrelatesAssetsHolderQueryController<PatrolExecutionEntity, String> {
    private final PatrolExecutionService service;
    private final PatrolExecutionWorkflow workflow;

    @Nonnull
    public AssetType getAssetType() {
        return PatrolAssetType.patrolPlan;
    }

    @Nonnull
    public Function<PatrolExecutionEntity, ?> getAssetIdMapper() {
        return PatrolExecutionEntity::getPlanId;
    }

    @Nonnull
    public String getAssetProperty() {
        return "planId";
    }

    public ReactiveCrudService<PatrolExecutionEntity, String> getService() {
        return service;
    }

    public record SubmitRequest(
            @NotBlank String taskId,
            @NotBlank @Size(max = 64) String requestId,
            @NotNull @Size(max = 100) @Valid List<@NotNull Answer> answers,
            @Size(max = 1000) String remark) {}

    @PostMapping
    @ResourceAction(id = "submit", name = "提交巡更结果")
    public Mono<PatrolExecutionEntity> submit(@RequestBody @Valid SubmitRequest request) {
        return workflow.submit(
                new PatrolExecutionWorkflow.Submission(
                        request.taskId(),
                        request.requestId(),
                        request.answers(),
                        request.remark()));
    }
}
