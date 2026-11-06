package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.activity.ActivityInput;
import org.jetlinks.pro.property.finance.entity.ActivityLogEntity;
import org.jetlinks.pro.property.finance.service.ActivityService;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.util.Map;

/** 直接活动设置的受权入口，无活动申请、审批回调或通用物理删除接口。 */
@RestController
@RequestMapping("/property/finance/projects/{projectId}/activities")
@Resource(id = "property-finance-activity", name = "活动设置")
@Authorize
@RequiredArgsConstructor
public class ActivityController {
    private final ActivityService service;

    @GetMapping("/_options")
    @ResourceAction(id = "query", name = "查询")
    public Mono<Map<String, Object>> options(@PathVariable String projectId) {
        return service.options(projectId);
    }

    @PostMapping("/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<ActivityService.Summary>> query(
            @PathVariable String projectId, @RequestBody QueryParamEntity input) {
        return service.query(projectId, input);
    }

    @GetMapping("/{id}")
    @ResourceAction(id = "query", name = "查询")
    public Mono<ActivityService.Detail> detail(
            @PathVariable String projectId, @PathVariable String id) {
        return service.detail(projectId, id);
    }

    @PostMapping
    @ResourceAction(id = "save", name = "保存")
    public Mono<ActivityService.Detail> create(
            @PathVariable String projectId, @RequestBody ActivityInput input) {
        return service.save(projectId, null, input);
    }

    @PutMapping("/{id}")
    @ResourceAction(id = "save", name = "保存")
    public Mono<ActivityService.Detail> update(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody ActivityInput input) {
        return service.save(projectId, id, input);
    }

    @PutMapping("/{id}/_state")
    @ResourceAction(id = "state", name = "启用停用")
    public Mono<ActivityService.Detail> state(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody StateInput input) {
        if (input.enabled() == null)
            return Mono.error(
                    org.jetlinks.pro.property.finance.service.FinanceScope.error(
                            "activity_invalid", 400));
        return service.state(projectId, id, input.version(), input.enabled());
    }

    @PutMapping("/{id}/_extend")
    @ResourceAction(id = "date", name = "调整活动时间")
    public Mono<ActivityService.Detail> extend(
            @PathVariable String projectId, @PathVariable String id, @RequestBody DateInput input) {
        return service.changeEnd(projectId, id, input.version(), input.endDate(), true);
    }

    @PutMapping("/{id}/_end")
    @ResourceAction(id = "date", name = "调整活动时间")
    public Mono<ActivityService.Detail> end(
            @PathVariable String projectId, @PathVariable String id, @RequestBody DateInput input) {
        return service.changeEnd(projectId, id, input.version(), input.endDate(), false);
    }

    @DeleteMapping("/{id}")
    @ResourceAction(id = "delete", name = "删除")
    public Mono<Void> delete(
            @PathVariable String projectId, @PathVariable String id, @RequestParam long version) {
        return service.delete(projectId, id, version);
    }

    @PostMapping("/{id}/logs/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<ActivityLogEntity>> logs(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody QueryParamEntity input) {
        return service.history(projectId, id, input);
    }

    public record StateInput(Long version, Boolean enabled) {}

    public record DateInput(Long version, String endDate) {}
}
