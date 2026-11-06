package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.report.*;
import org.jetlinks.pro.property.finance.service.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** 已收台账复用统一现金来源；使用收款查询权限，不要求用户获得统计报表菜单权限。 */
@RestController
@Authorize
@RequiredArgsConstructor
@RequestMapping("/property/finance/projects/{projectId}/received")
@Resource(id = "property-finance-cashier", name = "收银台")
public class ReceivedLedgerController {
    private final ReceivedReportService reports;
    private final ReceivedReportExportService exports;

    @PostMapping("/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<ReceivedReportResult> query(
            @PathVariable String projectId, @RequestBody ReceivedReportInput input) {
        scope(projectId, input);
        return reports.query(input);
    }

    @PostMapping("/_export")
    @ResourceAction(id = "export", name = "导出")
    public Mono<ResponseEntity<byte[]>> export(
            @PathVariable String projectId, @RequestBody ReceivedReportInput input) {
        scope(projectId, input);
        return exports.export(input, "excel")
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
                                                                "已收管理.xlsx", StandardCharsets.UTF_8)
                                                        .build()
                                                        .toString())
                                        .body(bytes));
    }

    private static void scope(String project, ReceivedReportInput input) {
        if (input == null) throw FinanceScope.error("report_query_invalid", 400);
        // 客户端不能把项目路由伪造成跨项目报表，也不能用分组查询改变台账行语义。
        input.setProjectIds(List.of(project));
        input.setGroupBy("DETAIL");
    }
}
