package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.ResourceAction;
import org.jetlinks.pro.property.finance.entity.TaxImportEntity;
import org.jetlinks.pro.property.finance.service.TaxImportService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

/** 接收原始Excel文件，不接受前端解析行；文件解析在线程池执行，写入整批事务。 */
@RestController
@RequestMapping("/property/finance/projects/{projectId}/tax-items")
@Resource(id = "property-finance-tax", name = "收费税目设置")
@Authorize
@RequiredArgsConstructor
public class TaxImportController {
    private final TaxImportService imports;

    @GetMapping("/template.xlsx")
    @ResourceAction(id = "import", name = "导入")
    public Mono<Void> template(@PathVariable String projectId, ServerHttpResponse response) {
        return imports.template(projectId)
                .flatMap(
                        bytes -> {
                            response.getHeaders()
                                    .setContentType(
                                            MediaType.parseMediaType(
                                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
                            response.getHeaders()
                                    .set(
                                            "Content-Disposition",
                                            "attachment; filename=tax-items-template.xlsx");
                            return response.writeWith(
                                    Mono.just(response.bufferFactory().wrap(bytes)));
                        });
    }

    @PostMapping(value = "/_import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResourceAction(id = "import", name = "导入")
    public Mono<TaxImportService.Result> upload(
            @PathVariable String projectId,
            @RequestParam String companyId,
            @RequestPart("file") FilePart file) {
        return imports.upload(projectId, companyId, file);
    }

    @PostMapping("/imports/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<TaxImportEntity>> records(
            @PathVariable String projectId, @RequestBody QueryParamEntity query) {
        return imports.records(projectId, query);
    }
}
