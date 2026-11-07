package org.jetlinks.pro.property.finance.service;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.i18n.LocaleUtils;
import org.jetlinks.pro.property.finance.report.*;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;

/** 数据库事务结束再有界排版，避免文件渲染长期占用数据库连接。 */
@Service
@RequiredArgsConstructor
public class InvoiceReportExportService {
    private final InvoiceReportService reports;
    private final InvoiceReportFile files;

    public Mono<byte[]> export(InvoiceReportInput input, String format) {
        if (!Set.of("excel", "pdf").contains(format))
            return Mono.error(FinanceScope.error("report_query_invalid", 400));
        return LocaleUtils.currentReactive()
                .defaultIfEmpty(Locale.SIMPLIFIED_CHINESE)
                .flatMap(locale -> reports.exportWorkbook(input, locale, files))
                .flatMap(
                        book ->
                                Mono.fromCallable(() -> FinanceReportFile.write(book, format))
                                        .subscribeOn(Schedulers.boundedElastic()));
    }
}
