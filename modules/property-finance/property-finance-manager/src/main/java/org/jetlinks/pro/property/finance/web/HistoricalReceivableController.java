package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.entity.ReceivableImportEntity;
import org.jetlinks.pro.property.finance.service.HistoricalReceivableService;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

/** 模板、整批历史入账与批次查询复用应收资源权限，项目身份始终取自路径。 */
@RestController
@RequiredArgsConstructor
@Authorize
@RequestMapping("/property/finance/projects/{projectId}/receivables/imports")
@Resource(id = "property-finance-receivable", name = "应收款管理")
public class HistoricalReceivableController {
    private final HistoricalReceivableService service;
    private final org.jetlinks.pro.property.finance.service.FinanceImportQueue queue;

    @GetMapping("/template.xlsx")
    @ResourceAction(id = "import", name = "导入")
    public Mono<Void> template(@PathVariable String projectId, ServerHttpResponse response) {
        response.getHeaders()
                .set(
                        "Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.getHeaders()
                .set(
                        "Content-Disposition",
                        "attachment; filename=historical-receivable-template.xlsx");
        return response.writeWith(service.template(projectId));
    }

    @PostMapping
    @ResourceAction(id = "import", name = "导入")
    public Mono<ReceivableImportEntity> upload(
            @PathVariable String projectId, @RequestPart("file") FilePart file) {
        return queue.uploadHistorical(projectId, file);
    }

    @PostMapping("/{recordId}/_retry")
    @ResourceAction(id = "import", name = "导入")
    public Mono<Void> retry(@PathVariable String projectId, @PathVariable String recordId) {
        return queue.retry(projectId, "HISTORICAL", recordId);
    }

    @PostMapping("/_query")
    @ResourceAction(id = "imports", name = "导入记录")
    public Mono<PagerResult<ReceivableImportEntity>> records(
            @PathVariable String projectId, @RequestBody QueryParamEntity query) {
        return service.records(projectId, query);
    }
}
