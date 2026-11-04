package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.service.ReceivableBatchService;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

/** 不接收前端金额；批量删除与催缴预览均在项目及每个空间授权内执行。 */
@RestController
@RequiredArgsConstructor
@Authorize
@RequestMapping("/property/finance/projects/{projectId}/receivables")
@Resource(id = "property-finance-receivable", name = "应收款管理")
public class ReceivableBatchController {
    private final ReceivableBatchService service;

    @PostMapping("/_batch-delete")
    @ResourceAction(id = "delete", name = "删除")
    public Mono<Void> delete(
            @PathVariable String projectId, @RequestBody ReceivableBatchService.Input input) {
        return service.delete(projectId, input);
    }

    @PostMapping("/notice/_preview")
    @ResourceAction(id = "print", name = "催缴打印")
    public Mono<ReceivableBatchService.Notice> notice(
            @PathVariable String projectId, @RequestBody ReceivableBatchService.Input input) {
        return service.notice(projectId, input);
    }
}
