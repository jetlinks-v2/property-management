package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.receivable.*;
import org.jetlinks.pro.property.finance.service.ReceivableService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/** 应收管理仅开放业务接口，禁止通用CRUD修改金额、财务事实或物理删除。 */
@RestController
@RequiredArgsConstructor
@Authorize
@RequestMapping("/property/finance/projects/{projectId}/receivables")
@Resource(id = "property-finance-receivable", name = "应收款管理")
public class ReceivableController {
    private final ReceivableService service;

    @PostMapping("/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<ReceivableView>> query(
            @PathVariable String projectId, @RequestBody QueryParamEntity query) {
        return service.query(projectId, query);
    }

    @GetMapping("/{id}")
    @ResourceAction(id = "query", name = "查询")
    public Mono<ReceivableView> detail(@PathVariable String projectId, @PathVariable String id) {
        return service.detail(projectId, id);
    }

    @PostMapping("/{id}/logs/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<ReceivableLogView>> logs(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody QueryParamEntity query) {
        return service.logs(projectId, id, query);
    }

    @PostMapping("/{id}/_freeze")
    @ResourceAction(id = "freeze", name = "冻结")
    public Mono<Void> freeze(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody ReceivableService.ChangeInput input) {
        return service.change(projectId, id, input, "FREEZE");
    }

    @PostMapping("/{id}/_unfreeze")
    @ResourceAction(id = "freeze", name = "冻结")
    public Mono<Void> unfreeze(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody ReceivableService.ChangeInput input) {
        return service.change(projectId, id, input, "UNFREEZE");
    }

    @PostMapping("/{id}/_delete")
    @ResourceAction(id = "delete", name = "删除")
    public Mono<Void> delete(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody ReceivableService.ChangeInput input) {
        return service.change(projectId, id, input, "DELETE");
    }

    @PostMapping("/_export")
    @ResourceAction(id = "export", name = "导出")
    public Mono<ResponseEntity<byte[]>> export(
            @PathVariable String projectId, @RequestBody QueryParamEntity query) {
        return service.export(projectId, query)
                .map(
                        bytes ->
                                ResponseEntity.ok()
                                        .contentType(
                                                MediaType.parseMediaType(
                                                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                                        .header(
                                                HttpHeaders.CONTENT_DISPOSITION,
                                                ContentDisposition.attachment()
                                                        .filename(
                                                                "应收款.xlsx", StandardCharsets.UTF_8)
                                                        .build()
                                                        .toString())
                                        .body(bytes));
    }
}
