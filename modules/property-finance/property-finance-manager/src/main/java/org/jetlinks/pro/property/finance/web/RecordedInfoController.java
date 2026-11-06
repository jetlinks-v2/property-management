package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.ResourceAction;
import org.jetlinks.pro.property.finance.entity.RecordedInfoEntity;
import org.jetlinks.pro.property.finance.service.RecordedInfoService;
import org.jetlinks.pro.property.finance.tax.RecordedInfoInput;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;

/** 禁止通用物理删除和无项目入口；每个动作由服务同时验证组织资产与业务关联。 */
@RestController
@RequestMapping("/property/finance/projects/{projectId}/recorded")
@Resource(id = "property-finance-recorded", name = "入账信息设置")
@Authorize
@RequiredArgsConstructor
public class RecordedInfoController {
    private final RecordedInfoService service;

    @PostMapping("/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<RecordedInfoEntity>> query(
            @PathVariable String projectId, @RequestBody QueryParamEntity query) {
        return service.query(projectId, query);
    }

    @GetMapping("/_options")
    @ResourceAction(id = "query", name = "查询")
    public Mono<RecordedInfoService.OptionsDetail> options(@PathVariable String projectId) {
        return service.options(projectId);
    }

    @GetMapping("/{id}")
    @ResourceAction(id = "query", name = "查询")
    public Mono<RecordedInfoService.Detail> detail(
            @PathVariable String projectId, @PathVariable String id) {
        return service.detail(projectId, id);
    }

    @PostMapping
    @ResourceAction(id = "save", name = "保存")
    public Mono<RecordedInfoService.BatchResult> create(
            @PathVariable String projectId, @RequestBody RecordedInfoInput input) {
        return service.create(projectId, input);
    }

    @PutMapping("/{id}")
    @ResourceAction(id = "save", name = "保存")
    public Mono<RecordedInfoService.Detail> update(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody RecordedInfoInput input) {
        return service.update(projectId, id, input);
    }

    @DeleteMapping("/{id}")
    @ResourceAction(id = "delete", name = "删除")
    public Mono<Void> delete(
            @PathVariable String projectId, @PathVariable String id, @RequestParam long version) {
        return service.delete(projectId, id, version);
    }

    @PostMapping("/{id}/_preview-fee")
    @ResourceAction(id = "query", name = "预览手续费")
    public Mono<BigDecimal> preview(
            @PathVariable String projectId, @PathVariable String id, @RequestBody Preview input) {
        return service.preview(projectId, id, input.amount());
    }

    public record Preview(BigDecimal amount) {}
}
