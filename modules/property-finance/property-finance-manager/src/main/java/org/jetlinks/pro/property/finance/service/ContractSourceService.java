package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import com.fasterxml.jackson.databind.JsonNode;

import lombok.RequiredArgsConstructor;

import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.property.finance.contract.ContractMirrorInput;
import org.jetlinks.pro.property.finance.contract.ContractSyncResult;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.*;
import java.util.*;

/** 外部资管只读HTTP适配；下载不占数据库事务，应用时用下载前版本防止旧结果覆盖。 */
@Service
@RequiredArgsConstructor
public class ContractSourceService {
    private final ContractSourceProperties properties;
    private final WebClient.Builder clients;
    private final ContractMirrorService mirror;

    public Mono<ContractSyncResult> sync(String project, String start, String end) {
        return Mono.defer(
                        () -> {
                            validateDates(start, end);
                            return mirror.version(project)
                                    .flatMap(
                                            version ->
                                                    download(project, start, end)
                                                            .flatMap(
                                                                    data ->
                                                                            mirror.apply(
                                                                                    project,
                                                                                    version,
                                                                                    data)));
                        })
                .as(MonoTracer.create("/property/finance/contracts/sync"));
    }

    private Mono<List<ContractMirrorInput>> download(String project, String start, String end) {
        return Mono.defer(
                () -> {
                    URI uri = sourceUri(project, start, end);
                    // 只聚合有界响应，不转发调用者令牌；来源错误不把原异常体或URL交给界面。
                    return clients.clone()
                            .codecs(
                                    config ->
                                            config.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                            .build()
                            .get()
                            .uri(uri)
                            .exchangeToMono(
                                    response ->
                                            response.statusCode().is2xxSuccessful()
                                                    ? response.bodyToMono(JsonNode.class)
                                                    : response.releaseBody()
                                                            .then(
                                                                    Mono.error(
                                                                            error(
                                                                                    "contract_source_invalid",
                                                                                    502))))
                            .switchIfEmpty(Mono.error(error("contract_source_invalid", 502)))
                            .timeout(Duration.ofSeconds(20))
                            .map(json -> decode(project, json))
                            .onErrorMap(failure -> error("contract_source_invalid", 502));
                });
    }

    private URI sourceUri(String project, String start, String end) {
        if (properties.getUrl() == null || properties.getUrl().isBlank())
            throw error("contract_source_unavailable", 503);
        try {
            var base = URI.create(properties.getUrl());
            if (!Set.of("http", "https").contains(base.getScheme())
                    || base.getHost() == null
                    || base.getRawUserInfo() != null
                    || base.getRawQuery() != null
                    || base.getRawFragment() != null) throw new IllegalArgumentException();
            return UriComponentsBuilder.fromUri(base)
                    .queryParam("projectId", project)
                    .queryParam("startDate", start)
                    .queryParam("endDate", end)
                    .build()
                    .encode()
                    .toUri();
        } catch (IllegalArgumentException failure) {
            throw error("contract_source_unavailable", 503);
        }
    }

    private List<ContractMirrorInput> decode(String project, JsonNode response) {
        if (!response.isObject()
                || !response.path("code").isIntegralNumber()
                || !response.path("code").canConvertToInt()
                || response.path("code").intValue() != 0
                || !response.path("data").isArray()
                || response.path("data").size() > 1000) throw error("contract_source_invalid", 502);
        var rows = new ArrayList<ContractMirrorInput>();
        for (var row : response.path("data")) {
            var deleted = row.path("isDelete");
            if (!deleted.isIntegralNumber()
                    || !deleted.canConvertToInt()
                    || deleted.intValue() < 0
                    || deleted.intValue() > 1) throw error("contract_source_invalid", 502);
            rows.add(
                    new ContractMirrorInput(
                            text(row, "id"),
                            text(row, "concatNo"),
                            text(row, "concatName"),
                            text(row, "projectId"),
                            text(row, "houseId"),
                            optional(row, "fullName"),
                            text(row, "customer"),
                            optional(row, "customerName"),
                            text(row, "startDate"),
                            text(row, "endDate"),
                            number(row, "leaseArea"),
                            deleted.intValue() == 1));
        }
        // 复用增量命令的字段规范；即便最后一行无效也不开始本地写入。
        return ContractMirrorService.validate(project, rows);
    }

    private static String text(JsonNode row, String field) {
        if (!row.path(field).isTextual()) throw error("contract_source_invalid", 502);
        return row.path(field).textValue();
    }

    private static String number(JsonNode row, String field) {
        if (!row.path(field).isTextual() && !row.path(field).isNumber())
            throw error("contract_source_invalid", 502);
        return row.path(field).asText();
    }

    private static String optional(JsonNode row, String field) {
        if (row.path(field).isMissingNode() || row.path(field).isNull()) return null;
        return text(row, field);
    }

    private static void validateDates(String start, String end) {
        try {
            var a = LocalDate.parse(start);
            var b = LocalDate.parse(end);
            if (a.isAfter(b) || a.getYear() < 1900 || b.getYear() > 9999)
                throw new IllegalArgumentException();
        } catch (RuntimeException failure) {
            throw error("contract_sync_dates_invalid", 400);
        }
    }
}
