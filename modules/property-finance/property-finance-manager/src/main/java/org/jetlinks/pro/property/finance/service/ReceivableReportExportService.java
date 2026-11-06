package org.jetlinks.pro.property.finance.service;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.i18n.LocaleUtils;
import org.jetlinks.pro.property.finance.report.*;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;

/** 数据快照事务结束后再进行有界文件渲染，不在排版期间占用数据库连接。 */
@Service
@RequiredArgsConstructor
public class ReceivableReportExportService {
    private final ReceivableReportService reports;
    private final ReceivableReportFile files;

    public Mono<byte[]> export(ReceivableReportInput input, String format) {
        if (!Set.of("excel", "pdf").contains(format))
            return Mono.error(FinanceScope.error("report_query_invalid", 400));
        return reports.exportData(input)
                .zipWith(LocaleUtils.currentReactive().defaultIfEmpty(Locale.SIMPLIFIED_CHINESE))
                .flatMap(
                        pair ->
                                Mono.fromCallable(
                                                () ->
                                                        files.write(
                                                                pair.getT1(), format, pair.getT2()))
                                        .subscribeOn(Schedulers.boundedElastic()));
    }
}
