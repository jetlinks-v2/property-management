package org.jetlinks.pro.property.finance.service;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.jetlinks.pro.property.finance.report.*;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.util.*;

/** 分页读取复用页面查询；每页200行，外层事务负责整份文件的同一账务快照。 */
@Component
@RequiredArgsConstructor
class ReportExportPages {
    private final FinanceAnalysisService analysis;
    private final ReceivableReportService receivables;
    private final ReceivedReportService received;
    private final InvoiceReportService invoices;

    record Page(FinanceReportFile.Data data, int total, List<String> spaces) {}

    Mono<Page> read(String kind, String json, int index, boolean detail) {
        if (Set.of("arrears", "monthly", "funds", "activity", "changes").contains(kind)) {
            var i = JSON.parseObject(json, AnalysisInput.class);
            i.setPageIndex(index);
            i.setPageSize(200);
            if (detail) i.setGroupBy(kind.equals("activity") ? "PERIOD" : "DETAIL");
            return analysis.query(kind, i)
                    .map(
                            r -> {
                                var labels = new LinkedHashMap<String, String>();
                                var money = new HashSet<String>();
                                r.columns()
                                        .forEach(
                                                c -> {
                                                    labels.put(c.key(), c.label());
                                                    if (c.money()) money.add(c.key());
                                                });
                                List<Map<String, String>> rows =
                                        r.page().getData().stream()
                                                .map(ReportExportPages::strings)
                                                .toList();
                                var data =
                                        new FinanceReportFile.Data(
                                                title(kind),
                                                "金额单位：元",
                                                "合计",
                                                new ArrayList<>(labels.keySet()),
                                                labels,
                                                money,
                                                rows,
                                                strings(r.summary()));
                                return new Page(
                                        data, r.page().getTotal(), spaces(r.page().getData()));
                            });
        }
        if (kind.equals("receivables")) {
            var i = JSON.parseObject(json, ReceivableReportInput.class);
            i.setPageIndex(index);
            i.setPageSize(200);
            if (detail) i.setGroupBy("DETAIL");
            return receivables
                    .query(i)
                    .map(
                            r ->
                                    new Page(
                                            new ReceivableReportFile()
                                                    .data(r, Locale.SIMPLIFIED_CHINESE),
                                            r.page().getTotal(),
                                            r.page().getData().stream()
                                                    .map(ReceivableReportRow::spaceId)
                                                    .filter(Objects::nonNull)
                                                    .distinct()
                                                    .toList()));
        }
        if (kind.equals("received")) {
            var i = JSON.parseObject(json, ReceivedReportInput.class);
            i.setPageIndex(index);
            i.setPageSize(200);
            if (detail) i.setGroupBy("DETAIL");
            return received.query(i)
                    .map(
                            r ->
                                    new Page(
                                            new ReceivedReportFile()
                                                    .data(r, Locale.SIMPLIFIED_CHINESE),
                                            r.page().getTotal(),
                                            r.page().getData().stream()
                                                    .map(ReceivedReportRow::spaceId)
                                                    .filter(Objects::nonNull)
                                                    .distinct()
                                                    .toList()));
        }
        var i = JSON.parseObject(json, InvoiceReportInput.class);
        i.setPageIndex(index);
        i.setPageSize(200);
        if (detail) i.setGroupBy("DETAIL");
        return invoices.query(i)
                .map(
                        r ->
                                new Page(
                                        new InvoiceReportFile().data(r, Locale.SIMPLIFIED_CHINESE),
                                        r.page().getTotal(),
                                        r.page().getData().stream()
                                                .map(InvoiceReportRow::spaceId)
                                                .filter(Objects::nonNull)
                                                .distinct()
                                                .toList()));
    }

    static String title(String kind) {
        return switch (kind) {
            case "arrears" -> "欠费分析";
            case "monthly" -> "财务月报";
            case "funds" -> "资金统计";
            case "activity" -> "活动优惠统计";
            case "changes" -> "账务变动统计";
            case "receivables" -> "应收统计";
            case "received" -> "实收统计";
            default -> "开票统计";
        };
    }

    private static Map<String, String> strings(Map<String, ?> source) {
        var out = new LinkedHashMap<String, String>();
        source.forEach((k, v) -> out.put(k, AnalysisLabels.value(k, Objects.toString(v, ""))));
        return out;
    }

    private static List<String> spaces(List<Map<String, Object>> rows) {
        return rows.stream()
                .map(r -> Objects.toString(r.get("spaceId"), null))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }
}
