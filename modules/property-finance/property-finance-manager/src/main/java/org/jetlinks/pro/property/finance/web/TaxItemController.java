package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.ResourceAction;
import org.jetlinks.pro.property.finance.entity.TaxItemEntity;
import org.jetlinks.pro.property.finance.entity.TaxOperationEntity;
import org.jetlinks.pro.property.finance.service.TaxItemService;
import org.jetlinks.pro.property.finance.tax.TaxItemInput;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.util.List;

/** 公司共享税目的项目授权入口；不开放通用物理删除或外部同步成功占位接口。 */
@RestController
@RequestMapping("/property/finance/projects/{projectId}/tax-items")
@Resource(id = "property-finance-tax", name = "收费税目设置")
@Authorize
@RequiredArgsConstructor
public class TaxItemController {
    private final TaxItemService service;

    @PostMapping("/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<TaxItemEntity>> query(
            @PathVariable String projectId, @RequestBody QueryParamEntity query) {
        return service.query(projectId, query);
    }

    @GetMapping("/{id}")
    @ResourceAction(id = "query", name = "查询")
    public Mono<TaxItemEntity> detail(@PathVariable String projectId, @PathVariable String id) {
        return service.detail(projectId, id);
    }

    @PostMapping
    @ResourceAction(id = "save", name = "保存")
    public Mono<TaxItemEntity> create(
            @PathVariable String projectId, @RequestBody TaxItemInput input) {
        return service.create(projectId, input);
    }

    @PutMapping("/{id}")
    @ResourceAction(id = "save", name = "保存")
    public Mono<TaxItemEntity> update(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody TaxItemInput input) {
        return service.update(projectId, id, input);
    }

    @PutMapping("/{id}/state")
    @ResourceAction(id = "save", name = "保存")
    public Mono<TaxItemEntity> state(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody StateInput input) {
        if (input.version() == null || input.enabled() == null)
            return Mono.error(
                    org.jetlinks.pro.property.finance.service.FinanceScope.error(
                            "tax_invalid_state", 400));
        return service.state(projectId, id, input.version(), input.enabled());
    }

    @DeleteMapping("/{id}")
    @ResourceAction(id = "delete", name = "删除")
    public Mono<Void> delete(
            @PathVariable String projectId, @PathVariable String id, @RequestParam Long version) {
        return service.delete(projectId, id, version);
    }

    @PostMapping("/_delete")
    @ResourceAction(id = "delete", name = "删除")
    public Mono<Void> deleteBatch(
            @PathVariable String projectId, @RequestBody List<TaxItemService.VersionedId> input) {
        return service.deleteBatch(projectId, input);
    }

    @PostMapping("/{id}/logs/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<TaxOperationEntity>> logs(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody QueryParamEntity query) {
        return service.logs(projectId, id, query);
    }

    public record StateInput(Long version, Boolean enabled) {}
}
