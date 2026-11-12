package org.jetlinks.pro.patrol.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.service.PatrolTaskService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.Set;

/** 按任务逻辑日期统计应巡事实，不把申请、豁免或人员评价混入完成次数。 */
@RestController
@AllArgsConstructor
@RequestMapping("/park/patrol/reports")
@Resource(id = "patrol-report", name = "巡更完成统计")
public class PatrolReportController {
    private final PatrolTaskService tasks;

    public record Range(@NotNull LocalDate from, @NotNull LocalDate to) {}

    public record Summary(
            long windows,
            long expected,
            long completed,
            long completeWindows,
            long overdueMissing) {}

    @PostMapping("/summary")
    @QueryAction
    public Mono<Summary> summary(@RequestBody @Valid Range range) {
        if (range.to().isBefore(range.from()) || range.to().isAfter(range.from().plusDays(30)))
            return Mono.error(new BusinessException("error.patrol.report.range"));
        QueryParamEntity query =
                QueryParamEntity.newQuery()
                        .between("businessDate", range.from().toString(), range.to().toString())
                        .getParam();
        query.setPaging(false);
        query.setIncludes(Set.of("requiredCount", "completedCount", "endTime"));
        long now = System.currentTimeMillis();
        // 在数据库查询前注入授权；流式折叠，不把全量任务收集到内存。
        return AssetsHolder.injectQueryParam(query, PatrolAssetType.patrolPlan, "planId")
                .flatMapMany(tasks::query)
                .reduce(
                        new Summary(0, 0, 0, 0, 0),
                        (sum, task) ->
                                new Summary(
                                        sum.windows() + 1,
                                        sum.expected() + task.getRequiredCount(),
                                        sum.completed() + task.getCompletedCount(),
                                        sum.completeWindows()
                                                + (task.getCompletedCount()
                                                                >= task.getRequiredCount()
                                                        ? 1
                                                        : 0),
                                        sum.overdueMissing()
                                                + (task.getEndTime() <= now
                                                        ? Math.max(
                                                                0,
                                                                task.getRequiredCount()
                                                                        - task.getCompletedCount())
                                                        : 0)));
    }
}
