package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.document.AllocationInput;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.service.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.util.List;

/** 项目票据分配与池维护；所有删除经受保护的逻辑删除入口，不继承通用CRUD。 */
@RestController
@RequiredArgsConstructor
@Authorize
@RequestMapping("/property/finance/projects/{projectId}/allocations")
@Resource(id = "property-finance-allocation", name = "票据分配")
public class AllocationController {
    private final AllocationService service;

    @PostMapping("/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<AllocationBookEntity>> query(
            @PathVariable String projectId, @RequestBody QueryParamEntity input) {
        return service.query(projectId, input);
    }

    @GetMapping("/_options")
    @ResourceAction(id = "query", name = "查询")
    public Mono<AllocationService.Options> options(@PathVariable String projectId) {
        return service.options(projectId);
    }

    @GetMapping("/recipients/_query")
    @ResourceAction(id = "save", name = "保存")
    public Mono<PagerResult<AllocationRecipientSource.Recipient>> recipients(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "0") int pageIndex,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keywords) {
        return service.recipients(projectId, pageIndex, pageSize, keywords);
    }

    @GetMapping("/{id}")
    @ResourceAction(id = "query", name = "查询")
    public Mono<AllocationBookEntity> detail(
            @PathVariable String projectId, @PathVariable String id) {
        return service.detail(projectId, id);
    }

    @PostMapping
    @ResourceAction(id = "save", name = "保存")
    public Mono<List<AllocationBookEntity>> create(
            @PathVariable String projectId, @RequestBody List<AllocationInput> input) {
        return service.create(projectId, input);
    }

    @PutMapping("/{id}")
    @ResourceAction(id = "save", name = "保存")
    public Mono<AllocationBookEntity> update(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody AllocationInput input) {
        return service.update(projectId, id, input);
    }

    @DeleteMapping("/{id}")
    @ResourceAction(id = "delete", name = "删除")
    public Mono<Void> delete(
            @PathVariable String projectId, @PathVariable String id, @RequestParam long version) {
        return service.delete(projectId, id, version);
    }

    @PostMapping("/{id}/numbers/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<AllocationNumberEntity>> pool(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody QueryParamEntity input) {
        return service.pool(projectId, id, input);
    }

    @PostMapping("/{id}/logs/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<AllocationLogEntity>> logs(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody QueryParamEntity input) {
        return service.logs(projectId, id, input);
    }

    @PutMapping("/{id}/numbers/{numberId}/_state")
    @ResourceAction(id = "pool", name = "维护票据池")
    public Mono<AllocationNumberEntity> state(
            @PathVariable String projectId,
            @PathVariable String id,
            @PathVariable String numberId,
            @RequestBody AllocationService.NumberState input) {
        return service.numberState(projectId, id, numberId, input);
    }
}
