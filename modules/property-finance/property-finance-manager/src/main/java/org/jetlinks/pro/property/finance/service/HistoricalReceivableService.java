package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.*;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.crud.query.QueryHelper;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.archive.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.receivable.*;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.core.io.buffer.*;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.LocalDate;
import java.util.*;

/** 全表预检通过后暂存待审核；审核通过才发布正式应收，暂存和审计共用响应式事务。 */
@Service
@RequiredArgsConstructor
public class HistoricalReceivableService {
    private final FinanceScope scope;
    private final FinancialPeriodGate periodGate;
    private final HouseArchiveSource archive;
    private final ReactiveRepository<StandardEntity, String> standards;
    private final ReactiveRepository<SubjectEntity, String> subjects;
    private final ReactiveRepository<ProjectSubjectEntity, String> issued;
    private final ReactiveRepository<ReceivableEntity, String> bills;
    private final ReactiveRepository<ReceivableLogEntity, String> logs;
    private final ReactiveRepository<ReceivableImportEntity, String> records;
    private final ReactiveRepository<ReceivableImportLineEntity, String> drafts;
    private final ReactiveTransactionManager transactions;

    /** 全文件校验落阶段文件，结束后再按100行事务暂存；不在校验期锁住整个项目。 */
    Mono<Void> processLarge(
            String project,
            String id,
            java.nio.file.Path raw,
            java.nio.file.Path prepared,
            java.util.function.BiFunction<String, Integer, Mono<Void>> progress) {
        return scope.authenticated()
                .flatMap(
                        auth ->
                                records.findById(id)
                                        .flatMap(
                                                record -> {
                                                    var seen = new HashSet<String>();
                                                    var keys = new HashSet<String>();
                                                    var header = new boolean[1];
                                                    var staged = new int[1];
                                                    var input =
                                                            org.jetlinks.pro.property.finance
                                                                    .payment.FinanceExcelRows.read(
                                                                            raw)
                                                                    .concatMap(
                                                                            row -> {
                                                                                if (row.number()
                                                                                        == 1) {
                                                                                    if (!HistoricalWorkbook
                                                                                            .HEADERS
                                                                                            .equals(
                                                                                                    row
                                                                                                            .cells()))
                                                                                        return Mono
                                                                                                .error(
                                                                                                        HistoricalWorkbook
                                                                                                                .rowError(
                                                                                                                        1,
                                                                                                                        "表头",
                                                                                                                        "historical_header_invalid"));
                                                                                    header[0] =
                                                                                            true;
                                                                                    return Mono
                                                                                            .<HistoricalWorkbook
                                                                                                            .Line>
                                                                                                    empty();
                                                                                }
                                                                                if (!header[0])
                                                                                    return Mono
                                                                                            .error(
                                                                                                    HistoricalWorkbook
                                                                                                            .rowError(
                                                                                                                    1,
                                                                                                                    "表头",
                                                                                                                    "historical_header_invalid"));
                                                                                var line =
                                                                                        HistoricalWorkbook
                                                                                                .decode(
                                                                                                        row,
                                                                                                        seen);
                                                                                return line == null
                                                                                        ? Mono
                                                                                                .<HistoricalWorkbook
                                                                                                                .Line>
                                                                                                        empty()
                                                                                        : Mono.just(
                                                                                                line);
                                                                            },
                                                                            1);
                                                    var validated =
                                                            input.concatMap(
                                                                    line ->
                                                                            archive.houses(
                                                                                            project,
                                                                                            null,
                                                                                            line
                                                                                                    .fullName(),
                                                                                            0,
                                                                                            200)
                                                                                    .flatMap(
                                                                                            houses -> {
                                                                                                if (houses
                                                                                                                .total()
                                                                                                        > 200)
                                                                                                    return Mono
                                                                                                            .error(
                                                                                                                    HistoricalWorkbook
                                                                                                                            .rowError(
                                                                                                                                    line
                                                                                                                                            .row(),
                                                                                                                                    "房屋完整路径",
                                                                                                                                    "historical_match_invalid"));
                                                                                                return TransactionalOperator
                                                                                                        .create(
                                                                                                                transactions)
                                                                                                        .transactional(
                                                                                                                periodGate
                                                                                                                        .lock(
                                                                                                                                project)
                                                                                                                        .flatMap(
                                                                                                                                control ->
                                                                                                                                        periodGate
                                                                                                                                                .assertOpen(
                                                                                                                                                        control,
                                                                                                                                                        LocalDate
                                                                                                                                                                .now())
                                                                                                                                                .then(
                                                                                                                                                        periodGate
                                                                                                                                                                .assertOpen(
                                                                                                                                                                        control,
                                                                                                                                                                        line
                                                                                                                                                                                .start())))
                                                                                                                        .then(
                                                                                                                                prepareLine(
                                                                                                                                        project,
                                                                                                                                        record,
                                                                                                                                        line,
                                                                                                                                        houses
                                                                                                                                                .data(),
                                                                                                                                        auth)));
                                                                                            })
                                                                                    .timeout(
                                                                                            java
                                                                                                    .time
                                                                                                    .Duration
                                                                                                    .ofMinutes(
                                                                                                            2))
                                                                                    .map(
                                                                                            bill -> {
                                                                                                if (!keys
                                                                                                        .add(
                                                                                                                bill
                                                                                                                        .getBillKey()))
                                                                                                    throw HistoricalWorkbook
                                                                                                            .rowError(
                                                                                                                    line
                                                                                                                            .row(),
                                                                                                                    "房屋/客户/科目/标准/日期",
                                                                                                                    "historical_duplicate_file");
                                                                                                bill
                                                                                                        .setId(
                                                                                                                UUID.nameUUIDFromBytes(
                                                                                                                                (id
                                                                                                                                                + ":"
                                                                                                                                                + line
                                                                                                                                                        .row())
                                                                                                                                        .getBytes(
                                                                                                                                                StandardCharsets
                                                                                                                                                        .UTF_8))
                                                                                                                        .toString());
                                                                                                var
                                                                                                        draft =
                                                                                                                new ReceivableImportLineEntity();
                                                                                                init(
                                                                                                        draft,
                                                                                                        auth);
                                                                                                draft
                                                                                                        .setId(
                                                                                                                bill
                                                                                                                        .getId());
                                                                                                draft
                                                                                                        .setProjectId(
                                                                                                                project);
                                                                                                draft
                                                                                                        .setImportId(
                                                                                                                id);
                                                                                                draft
                                                                                                        .setSpaceId(
                                                                                                                bill
                                                                                                                        .getSpaceId());
                                                                                                draft
                                                                                                        .setFullName(
                                                                                                                bill
                                                                                                                        .getFullName());
                                                                                                draft
                                                                                                        .setCustomerName(
                                                                                                                bill
                                                                                                                        .getCustomerName());
                                                                                                draft
                                                                                                        .setLineNumber(
                                                                                                                line
                                                                                                                        .row());
                                                                                                draft
                                                                                                        .setStatus(
                                                                                                                "PENDING");
                                                                                                draft
                                                                                                        .setPayloadJson(
                                                                                                                JSON
                                                                                                                        .toJSONString(
                                                                                                                                bill));
                                                                                                return draft;
                                                                                            }),
                                                                    1);
                                                    return FinanceImportFiles.write(
                                                                    prepared,
                                                                    validated,
                                                                    n ->
                                                                            progress.apply(
                                                                                    "VALIDATING",
                                                                                    n))
                                                            .flatMap(
                                                                    total -> {
                                                                        if (total == 0)
                                                                            return Mono.error(
                                                                                    HistoricalWorkbook
                                                                                            .rowError(
                                                                                                    2,
                                                                                                    "文件",
                                                                                                    "historical_file_empty"));
                                                                        return records.createUpdate()
                                                                                .set(
                                                                                        "totalCount",
                                                                                        total)
                                                                                .where("id", id)
                                                                                .execute()
                                                                                .then(
                                                                                        progress
                                                                                                .apply(
                                                                                                        "STAGING",
                                                                                                        0))
                                                                                .thenMany(
                                                                                        FinanceImportFiles
                                                                                                .read(
                                                                                                        prepared,
                                                                                                        ReceivableImportLineEntity
                                                                                                                .class)
                                                                                                .buffer(
                                                                                                        100)
                                                                                                .concatMap(
                                                                                                        batch ->
                                                                                                                TransactionalOperator
                                                                                                                        .create(
                                                                                                                                transactions)
                                                                                                                        .transactional(
                                                                                                                                Flux
                                                                                                                                        .fromIterable(
                                                                                                                                                batch)
                                                                                                                                        .concatMap(
                                                                                                                                                draft ->
                                                                                                                                                        org
                                                                                                                                                                .jetlinks
                                                                                                                                                                .pro
                                                                                                                                                                .assets
                                                                                                                                                                .AssetsHolder
                                                                                                                                                                .assertPermission(
                                                                                                                                                                        org
                                                                                                                                                                                .jetlinks
                                                                                                                                                                                .pro
                                                                                                                                                                                .assets
                                                                                                                                                                                .AssetType
                                                                                                                                                                                .of(
                                                                                                                                                                                        "space"),
                                                                                                                                                                        draft
                                                                                                                                                                                .getSpaceId(),
                                                                                                                                                                        CrudAssetPermission
                                                                                                                                                                                .save)
                                                                                                                                                                .then(
                                                                                                                                                                        drafts
                                                                                                                                                                                .findById(
                                                                                                                                                                                        draft
                                                                                                                                                                                                .getId()))
                                                                                                                                                                .switchIfEmpty(
                                                                                                                                                                        drafts.insert(
                                                                                                                                                                                        draft)
                                                                                                                                                                                .thenReturn(
                                                                                                                                                                                        draft)),
                                                                                                                                                1)
                                                                                                                                        .then())
                                                                                                                        .then(
                                                                                                                                progress
                                                                                                                                        .apply(
                                                                                                                                                "STAGING",
                                                                                                                                                staged[
                                                                                                                                                                0] +=
                                                                                                                                                        batch
                                                                                                                                                                .size())),
                                                                                                        1))
                                                                                .then(
                                                                                        records.createUpdate()
                                                                                                .set(
                                                                                                        "status",
                                                                                                        "PENDING")
                                                                                                .set(
                                                                                                        "phase",
                                                                                                        "PENDING")
                                                                                                .set(
                                                                                                        "processedCount",
                                                                                                        total)
                                                                                                .set(
                                                                                                        "errorCount",
                                                                                                        0)
                                                                                                .set(
                                                                                                        "errorMessage",
                                                                                                        "")
                                                                                                .set(
                                                                                                        "finishTime",
                                                                                                        System
                                                                                                                .currentTimeMillis())
                                                                                                .where(
                                                                                                        "id",
                                                                                                        id)
                                                                                                .execute())
                                                                                .then();
                                                                    });
                                                }));
    }

    public Flux<DataBuffer> template(String project) {
        return scope.project(project, CrudAssetPermission.read)
                .flatMapMany(
                        info ->
                                houses(project, 0)
                                        .flatMapMany(Flux::fromIterable)
                                        .concatMap(
                                                house ->
                                                        archive.context(
                                                                project, house.spaceId(), false))
                                        .collectList()
                                        .flatMapMany(
                                                contexts ->
                                                        templateFor(
                                                                project, info.name(), contexts)));
    }

    private Flux<DataBuffer> templateFor(
            String project, String name, List<PropertyHouseContext> contexts) {
        if (contexts.isEmpty()) return Flux.error(error("historical_no_houses", 409));
        var customers =
                contexts.stream()
                        .flatMap(
                                context ->
                                        context.customers().stream()
                                                .map(
                                                        customer ->
                                                                List.of(
                                                                        context.house().fullName(),
                                                                        customer.name())))
                        .toList();
        if (customers.isEmpty()) return Flux.error(error("historical_no_customers", 409));
        return issued.createQuery()
                .where("projectId", project)
                .and("deleted", false)
                .fetch()
                .map(ProjectSubjectEntity::getSubjectId)
                .collectList()
                .flatMapMany(ids -> templateStandards(project, ids))
                .map(std -> List.of(std.getSubjectName(), std.getStandardName()))
                .collectList()
                .flatMapMany(rows -> HistoricalWorkbook.template(name, customers, rows));
    }

    private Flux<StandardEntity> templateStandards(String project, List<String> ids) {
        if (ids.isEmpty()) return Flux.error(error("historical_no_standards", 409));
        return standards
                .createQuery()
                .where("projectId", project)
                .and("deleted", false)
                .in("subjectId", ids)
                .fetch()
                .take(50001)
                .collectList()
                .flatMapMany(
                        rows -> {
                            if (rows.size() > 50000)
                                return Flux.error(error("historical_reference_limit", 400));
                            if (rows.isEmpty())
                                return Flux.error(error("historical_no_standards", 409));
                            return Flux.fromIterable(rows);
                        });
    }

    private Mono<List<PropertyHouseInfo>> houses(String project, int page) {
        return archive.houses(project, null, null, page, 200)
                .flatMap(
                        result -> {
                            if (result.total() > 50000)
                                return Mono.error(error("historical_reference_limit", 400));
                            if (result.data().isEmpty() && page * 200L < result.total())
                                return Mono.error(error("house_source_unavailable", 503));
                            if ((page + 1) * 200L >= result.total())
                                return Mono.just(result.data());
                            return houses(project, page + 1)
                                    .map(
                                            rest -> {
                                                var all = new ArrayList<>(result.data());
                                                all.addAll(rest);
                                                return all;
                                            });
                        });
    }

    public Mono<ReceivableImportEntity> importFile(String project, FilePart file) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth -> {
                            var record = new ReceivableImportEntity();
                            record.setId(UUID.randomUUID().toString());
                            init(record, auth);
                            record.setProjectId(project);
                            var filename = file == null ? "" : file.filename();
                            record.setFileName(
                                    filename.substring(0, Math.min(255, filename.length())));
                            record.setStatus("PROCESSING");
                            record.setTotalCount(0);
                            record.setSuccessCount(0);
                            record.setErrorCount(0);
                            record.setRejectedCount(0);
                            record.setOperatorId(auth.getUser().getId());
                            record.setOperatorName(FinanceScope.operatorName(auth));
                            // 批次必须先独立落库；只有业务处理失败才写失败结果，批次本身写失败直接返回异常。
                            return records.insert(record)
                                    .then(
                                            Mono.defer(
                                                    () ->
                                                            process(project, file, record, auth)
                                                                    .onErrorResume(
                                                                            failure ->
                                                                                    failed(
                                                                                            record,
                                                                                            failure))
                                                                    .then(
                                                                            records.findById(
                                                                                    record
                                                                                            .getId()))));
                        })
                .as(MonoTracer.create("/property/finance/receivable/historical-import"));
    }

    private Mono<Void> process(
            String project, FilePart file, ReceivableImportEntity record, Authentication auth) {
        return read(file)
                .flatMap(
                        bytes ->
                                Mono.fromCallable(() -> HistoricalWorkbook.parse(bytes))
                                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(
                        lines -> {
                            record.setTotalCount(lines.size());
                            return records.createUpdate()
                                    .set("totalCount", lines.size())
                                    .where("id", record.getId())
                                    .execute()
                                    .then(apply(project, record, lines, auth));
                        });
    }

    private Mono<byte[]> read(FilePart file) {
        return Mono.defer(
                () -> {
                    if (file == null
                            || file.filename().length() > 255
                            || !file.filename()
                                    .toLowerCase(Locale.ROOT)
                                    .matches(".*\\.(xlsx|xls)$"))
                        return Mono.error(error("historical_file_invalid", 400));
                    return DataBufferUtils.join(file.content(), 10 * 1024 * 1024)
                            .switchIfEmpty(Mono.error(error("historical_file_invalid", 400)))
                            .map(
                                    buffer -> {
                                        try {
                                            var bytes = new byte[buffer.readableByteCount()];
                                            buffer.read(bytes);
                                            return bytes;
                                        } finally {
                                            DataBufferUtils.release(buffer);
                                        }
                                    });
                });
    }

    /** 先期间再基础项目、科目与标准；已结行或任意匹配失败时整表回滚，失败批次单独保留。 */
    private Mono<Void> apply(
            String project,
            ReceivableImportEntity record,
            List<HistoricalWorkbook.Line> lines,
            Authentication auth) {
        var ordered =
                lines.stream()
                        .sorted(
                                Comparator.comparing(HistoricalWorkbook.Line::subjectName)
                                        .thenComparing(HistoricalWorkbook.Line::standardName)
                                        .thenComparing(HistoricalWorkbook.Line::fullName))
                        .toList();
        var prepared =
                periodGate
                        .lock(project)
                        .flatMap(
                                control ->
                                        periodGate
                                                .assertOpen(control, LocalDate.now())
                                                .then(validatePeriods(control, lines)))
                        .then(houses(project, 0))
                        .flatMapMany(
                                all ->
                                        Flux.fromIterable(ordered)
                                                .concatMap(
                                                        line ->
                                                                prepareLine(
                                                                        project, record, line, all,
                                                                        auth)))
                        .collectList()
                        .flatMapMany(
                                rows -> {
                                    var keys = new HashSet<String>();
                                    for (var row : rows)
                                        if (!keys.add(row.getBillKey()))
                                            return Flux.error(
                                                    error("historical_duplicate_file", 409));
                                    var staged = new ArrayList<ReceivableImportLineEntity>();
                                    for (int index = 0; index < rows.size(); index++) {
                                        var row = rows.get(index);
                                        var draft = new ReceivableImportLineEntity();
                                        init(draft, auth);
                                        draft.setId(row.getId());
                                        draft.setProjectId(project);
                                        draft.setSpaceId(row.getSpaceId());
                                        draft.setImportId(record.getId());
                                        draft.setFullName(row.getFullName());
                                        draft.setCustomerName(row.getCustomerName());
                                        draft.setLineNumber(ordered.get(index).row());
                                        draft.setStatus("PENDING");
                                        draft.setPayloadJson(JSON.toJSONString(row));
                                        staged.add(draft);
                                    }
                                    return Flux.fromIterable(staged)
                                            .concatMap(
                                                    draft -> {
                                                        var event =
                                                                log(
                                                                        JSON.parseObject(
                                                                                draft
                                                                                        .getPayloadJson(),
                                                                                ReceivableEntity
                                                                                        .class),
                                                                        auth);
                                                        event.setOperation("IMPORT_STAGE");
                                                        return drafts.insert(draft)
                                                                .then(logs.insert(event));
                                                    });
                                })
                        .then(
                                records.createUpdate()
                                        .set("status", "PENDING")
                                        .set("successCount", 0)
                                        .set("errorCount", 0)
                                        .set("finishTime", System.currentTimeMillis())
                                        .where("id", record.getId())
                                        .execute())
                        .then();
        return TransactionalOperator.create(transactions).transactional(prepared);
    }

    private Mono<Void> validatePeriods(
            FinancialPeriodControlEntity control, List<HistoricalWorkbook.Line> lines) {
        return Flux.fromIterable(lines)
                .concatMap(
                        line ->
                                periodGate
                                        .assertOpen(control, line.start())
                                        .onErrorMap(
                                                org.hswebframework.web.exception.BusinessException
                                                        .class,
                                                ignored ->
                                                        HistoricalWorkbook.rowError(
                                                                line.row(),
                                                                "计费开始日期",
                                                                "period_closed")))
                .then();
    }

    /** 待审行修改沿用导入时的项目、房屋、客户、科目及标准匹配规则。调用者持有期间和批次锁。 */
    Mono<ReceivableEntity> reprepare(
            String project,
            ReceivableImportEntity record,
            HistoricalWorkbook.Line line,
            Authentication auth) {
        return houses(project, 0).flatMap(all -> prepareLine(project, record, line, all, auth));
    }

    private Mono<ReceivableEntity> prepareLine(
            String project,
            ReceivableImportEntity record,
            HistoricalWorkbook.Line line,
            List<PropertyHouseInfo> houses,
            Authentication auth) {
        var matches =
                houses.stream().filter(house -> house.fullName().equals(line.fullName())).toList();
        if (matches.size() != 1)
            return Mono.error(
                    HistoricalWorkbook.rowError(line.row(), "房屋完整路径", "historical_match_invalid"));
        return archive.context(project, matches.get(0).spaceId(), true)
                .flatMap(context -> prepare(project, record, line, context, auth));
    }

    private Mono<ReceivableEntity> prepare(
            String project,
            ReceivableImportEntity record,
            HistoricalWorkbook.Line line,
            PropertyHouseContext context,
            Authentication auth) {
        var customers =
                context.customers().stream()
                        .filter(c -> c.name().equals(line.customerName()))
                        .toList();
        if (customers.size() != 1)
            return Mono.error(
                    HistoricalWorkbook.rowError(line.row(), "客户名称", "historical_match_invalid"));
        var customer = customers.get(0);
        return matchingSubject(project, line)
                .flatMap(subject -> matchingStandard(project, subject.getId(), line))
                .flatMap(
                        standard ->
                                bills.createQuery()
                                        .where("projectId", project)
                                        .and("spaceId", context.house().spaceId())
                                        .and("customerId", customer.customerId())
                                        .and("subjectId", standard.getSubjectId())
                                        .and("standardId", standard.getId())
                                        .and("calcStartDate", line.start().toString())
                                        .and("calcEndDate", line.end().toString())
                                        .count()
                                        .flatMap(
                                                count -> {
                                                    if (count > 0)
                                                        return Mono.error(
                                                                HistoricalWorkbook.rowError(
                                                                        line.row(),
                                                                        "房屋/客户/科目/标准/日期",
                                                                        "historical_duplicate"));
                                                    return Mono.just(
                                                            bill(
                                                                    project, record, line, context,
                                                                    customer, standard, auth));
                                                }));
    }

    private Mono<SubjectEntity> matchingSubject(String project, HistoricalWorkbook.Line line) {
        var query = new QueryParamEntity();
        query.setForUpdate(true);
        query.toQuery().orderByAsc("id");
        // 先取本项目已下发科目，再锁候选；不流式锁全系统同名科目并嵌套查询同一连接。
        // 后者在候选较多时会耗尽游标需求而挂起，也会不必要地锁住其他项目数据。
        return issued.createQuery()
                .where("projectId", project)
                .and("deleted", false)
                .fetch()
                .map(ProjectSubjectEntity::getSubjectId)
                .collectList()
                .flatMap(
                        ids ->
                                ids.isEmpty()
                                        ? Mono.just(List.<SubjectEntity>of())
                                        : subjects.createQuery()
                                                .setParam(query)
                                                .where("name", line.subjectName())
                                                .and("deleted", false)
                                                .in("id", ids)
                                                .fetch()
                                                .collectList())
                .flatMap(
                        matches ->
                                matches.size() == 1
                                        ? Mono.just(matches.get(0))
                                        : Mono.error(
                                                HistoricalWorkbook.rowError(
                                                        line.row(),
                                                        "收费科目",
                                                        "historical_match_invalid")));
    }

    private Mono<StandardEntity> matchingStandard(
            String project, String subject, HistoricalWorkbook.Line line) {
        var query = new QueryParamEntity();
        query.setForUpdate(true);
        query.toQuery().orderByAsc("id");
        return standards
                .createQuery()
                .setParam(query)
                .where("projectId", project)
                .and("subjectId", subject)
                .and("standardName", line.standardName())
                .and("deleted", false)
                .fetch()
                .collectList()
                .flatMap(
                        matches ->
                                matches.size() == 1
                                        ? Mono.just(matches.get(0))
                                        : Mono.error(
                                                HistoricalWorkbook.rowError(
                                                        line.row(),
                                                        "收费标准",
                                                        "historical_match_invalid")));
    }

    private ReceivableEntity bill(
            String project,
            ReceivableImportEntity record,
            HistoricalWorkbook.Line line,
            PropertyHouseContext context,
            PropertyHouseCustomer customer,
            StandardEntity standard,
            Authentication auth) {
        var row = new ReceivableEntity();
        row.setId(UUID.randomUUID().toString());
        init(row, auth);
        row.setProjectId(project);
        row.setSpaceId(context.house().spaceId());
        row.setFullName(context.house().fullName());
        row.setSubjectId(standard.getSubjectId());
        row.setSubjectName(standard.getSubjectName());
        row.setStandardId(standard.getId());
        row.setStandardName(standard.getStandardName());
        row.setConfigVersion(0);
        row.setSourceType("HISTORICAL_IMPORT");
        row.setImportId(record.getId());
        row.setAccountPeriod(line.accountPeriod());
        row.setContractCode(line.contractCode());
        row.setRemark(line.remark());
        row.setCustomerId(customer.customerId());
        row.setCustomerRelationshipId(customer.relationshipId());
        row.setCustomerName(customer.name());
        row.setPeriodStart(line.start().toString());
        row.setPeriodEnd(line.end().toString());
        row.setCalcStartDate(row.getPeriodStart());
        row.setCalcEndDate(row.getPeriodEnd());
        // 模板第八列原名“应收日期”，必须落为业务日期，不能仅作为到期日使用。
        row.setReceivableDate(line.due().toString());
        row.setDueDate(line.due().toString());
        row.setAccruedAsOfDate(line.end().toString());
        row.setCalculatedAsOfDate(LocalDate.now().toString());
        row.setPeriodStatus("CLOSED");
        row.setMoneyScale(2);
        row.setFrozen(false);
        row.setPaymentLocked(false);
        row.setBankCollectionLocked(false);
        row.setLateFeeLocked(false);
        row.setFinancialClosed(false);
        row.setDiscountLocked(false);
        row.setChargeLocked(false);
        row.setSplit(false);
        var wrapper = new BeanWrapperImpl(row);
        for (var field : wrapper.getPropertyDescriptors())
            if (field.getPropertyType() == BigDecimal.class)
                wrapper.setPropertyValue(field.getName(), BigDecimal.ZERO.setScale(2));
        row.setContractReceivableAmount(line.original());
        row.setAccruedReceivableAmount(line.original());
        row.setFeeReductionAmount(line.reduction());
        row.setActualReceivableAmount(line.actual());
        row.setArrears(line.actual());
        // 导入金额是独立历史事实；当前标准仅保存税务/逾期等原定义，不能用其单价覆盖历史金额。
        var frozen = JSON.parseObject(JSON.toJSONString(standard), StandardEntity.class);
        frozen.setFormulaExpression("${IMPORTED_CONTRACT_AMOUNT}");
        frozen.setFormulaDisplay("[费用_金额]=[导入原应收]");
        frozen.setChargeRoundType(2);
        frozen.setMantissa(0);
        frozen.setVariablesSnapshotJson(
                "[{\"variableCode\":\"IMPORTED_CONTRACT_AMOUNT\",\"variableName\":\"导入原应收\",\"enabled\":true}]");
        row.setSnapshotJson(
                JSON.toJSONString(
                        Map.of(
                                "house",
                                context.house(),
                                "customer",
                                org.jetlinks.pro.property.finance.setting.SettingRules
                                        .customerSnapshot(customer),
                                "standard",
                                frozen,
                                "sourceStandard",
                                standard,
                                "values",
                                Map.of("IMPORTED_CONTRACT_AMOUNT", line.original()),
                                "sourceType",
                                "HISTORICAL_IMPORT")));
        row.setBillKey(
                "HIST:"
                        + hash(
                                JSON.toJSONString(
                                        List.of(
                                                project,
                                                row.getSpaceId(),
                                                row.getCustomerId(),
                                                row.getSubjectId(),
                                                row.getStandardId(),
                                                line.start().toString(),
                                                line.end().toString()))));
        return row;
    }

    static ReceivableLogEntity log(ReceivableEntity row, Authentication auth) {
        var log = new ReceivableLogEntity();
        log.setId(UUID.randomUUID().toString());
        log.setProjectId(row.getProjectId());
        log.setSpaceId(row.getSpaceId());
        log.setReceivableId(row.getId());
        log.setOperation("IMPORT");
        log.setReason("历史应收导入");
        log.setBeforeVersion(0L);
        log.setAfterVersion(0L);
        log.setBeforeJson("{}");
        log.setAfterJson(ReceivableService.auditSnapshot(row));
        log.setDetailJson(JSON.toJSONString(Map.of("importId", row.getImportId())));
        log.setOperatorId(auth.getUser().getId());
        log.setOperatorName(FinanceScope.operatorName(auth));
        log.setCreateTime(System.currentTimeMillis());
        return log;
    }

    private Mono<Void> failed(ReceivableImportEntity record, Throwable failure) {
        var message =
                failure instanceof BusinessException business
                        ? org.hswebframework.web.i18n.LocaleUtils.resolveMessage(
                                business.getI18nCode(), business.getMessage(), business.getArgs())
                        : org.hswebframework.web.i18n.LocaleUtils.resolveMessage(
                                "error.property_finance_historical_failed",
                                "error.property_finance_historical_failed");
        return records.createUpdate()
                .set("status", "FAILED")
                .set("successCount", 0)
                .set("errorCount", 1)
                .set("errorMessage", message.substring(0, Math.min(8000, message.length())))
                .set("finishTime", System.currentTimeMillis())
                .where("id", record.getId())
                .execute()
                .then();
    }

    public Mono<PagerResult<ReceivableImportEntity>> records(
            String project, QueryParamEntity input) {
        return scope.project(project, CrudAssetPermission.read)
                .then(
                        Mono.defer(
                                () -> {
                                    var query = SubjectService.page(input);
                                    query.toNestQuery(
                                            nest ->
                                                    nest.and("projectId", project)
                                                            .and("deleted", false));
                                    query.toQuery().orderByDesc("createTime");
                                    return QueryHelper.queryPager(query, records::createQuery);
                                }));
    }

    private static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
