package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.carry.CarryForwardPlanInput;
import org.jetlinks.pro.property.finance.entity.CarryForwardPlanLogEntity;
import org.jetlinks.pro.property.finance.service.CarryForwardPlanService;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

/** 自动结转计划只开放受权项目下的业务动作；启停独立按钮权限，未继承物理删除接口。 */
@RestController
@RequestMapping("/property/finance/projects/{projectId}/carry-plans")
@Resource(id = "property-finance-carry", name = "自动结转")
@Authorize
@RequiredArgsConstructor
public class CarryForwardPlanController {
    private final CarryForwardPlanService service;

    @GetMapping("/_options")
    @ResourceAction(id = "query", name = "查询")
    public Mono<java.util.List<CarryForwardPlanService.SubjectOption>> options(
            @PathVariable String projectId) {
        return service.options(projectId);
    }

    @PostMapping("/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<CarryForwardPlanService.Detail>> query(
            @PathVariable String projectId, @RequestBody QueryParamEntity input) {
        return service.query(projectId, input);
    }

    @GetMapping("/{id}")
    @ResourceAction(id = "query", name = "查询")
    public Mono<CarryForwardPlanService.Detail> detail(
            @PathVariable String projectId, @PathVariable String id) {
        return service.detail(projectId, id);
    }

    @PostMapping
    @ResourceAction(id = "save", name = "保存")
    public Mono<CarryForwardPlanService.Detail> create(
            @PathVariable String projectId, @RequestBody CarryForwardPlanInput input) {
        return service.create(projectId, input);
    }

    @PutMapping("/{id}")
    @ResourceAction(id = "save", name = "保存")
    public Mono<CarryForwardPlanService.Detail> update(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody CarryForwardPlanInput input) {
        return service.update(projectId, id, input);
    }

    @PutMapping("/{id}/_state")
    @ResourceAction(id = "state", name = "启用停用")
    public Mono<CarryForwardPlanService.Detail> state(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody StateInput input) {
        if (input == null || input.version() == null || input.enabled() == null)
            return Mono.error(
                    org.jetlinks.pro.property.finance.service.FinanceScope.error(
                            "carry_plan_invalid", 400));
        return service.state(projectId, id, input.version(), input.enabled());
    }

    @DeleteMapping("/{id}")
    @ResourceAction(id = "delete", name = "删除")
    public Mono<Void> delete(
            @PathVariable String projectId, @PathVariable String id, @RequestParam long version) {
        return service.delete(projectId, id, version);
    }

    @PostMapping("/{id}/logs/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<CarryForwardPlanLogEntity>> history(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody QueryParamEntity input) {
        return service.history(projectId, id, input);
    }

    /** 启停请求只接受目标状态及并发版本，不能借此更换其他配置。 */
    public record StateInput(Long version, Boolean enabled) {}
}
