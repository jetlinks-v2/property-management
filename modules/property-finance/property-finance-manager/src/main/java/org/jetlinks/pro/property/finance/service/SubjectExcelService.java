package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.*;

import lombok.RequiredArgsConstructor;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.crud.query.QueryHelper;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.base.dictionary.PropertyDictionaryOption;
import org.jetlinks.pro.property.finance.entity.ArrearsConfigEntity;
import org.jetlinks.pro.property.finance.entity.SubjectCategoryEntity;
import org.jetlinks.pro.property.finance.entity.SubjectEntity;
import org.jetlinks.pro.property.finance.entity.SubjectImportEntity;
import org.jetlinks.pro.property.finance.subject.SubjectInput;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.*;
import java.util.*;

/** 有界 Excel I/O 在专用调度器执行；全部行校验后才写入，任意写入失败回滚整批。 */
@Service
@RequiredArgsConstructor
public class SubjectExcelService {
    private static final List<String> HEADERS =
            List.of("收费目录完整路径", "收费科目名称", "费用类型", "是否生效", "是否自动开票", "欠费通知等级", "备注");
    private static final int MAX_ROWS = 500;
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private final FinanceScope scope;
    private final SubjectCategoryService categories;
    private final SubjectService subjects;
    private final ArrearsService arrears;
    private final SubjectDictionaryService dictionaries;
    private final ReactiveRepository<SubjectEntity, String> subjectRows;
    private final ReactiveRepository<SubjectImportEntity, String> records;
    private final ReactiveTransactionManager transactionManager;

    public Mono<byte[]> template() {
        return references()
                .flatMap(
                        references ->
                                Mono.fromCallable(() -> template(references))
                                        .subscribeOn(Schedulers.boundedElastic()));
    }

    public Mono<ImportResult> importFile(FilePart file) {
        return scope.authenticated()
                .flatMap(
                        auth ->
                                Mono.defer(
                                        () -> {
                                            var record = new SubjectImportEntity();
                                            record.setId(UUID.randomUUID().toString());
                                            init(record, auth);
                                            var filename = file == null ? "" : file.filename();
                                            record.setFileName(
                                                    filename.substring(
                                                            0, Math.min(255, filename.length())));
                                            record.setStatus("PROCESSING");
                                            record.setSuccessCount(0);
                                            record.setErrorCount(0);
                                            // 批次先独立提交；失败记录不能随科目事务回滚。
                                            return records.insert(record)
                                                    .then(read(file))
                                                    .flatMap(
                                                            bytes ->
                                                                    references()
                                                                            .flatMap(
                                                                                    references ->
                                                                                            Mono
                                                                                                    .fromCallable(
                                                                                                            () ->
                                                                                                                    parse(
                                                                                                                            bytes,
                                                                                                                            references))
                                                                                                    .subscribeOn(
                                                                                                            Schedulers
                                                                                                                    .boundedElastic())))
                                                    .flatMap(
                                                            rows ->
                                                                    records.createUpdate()
                                                                            .set(
                                                                                    "totalCount",
                                                                                    rows.size())
                                                                            .where(
                                                                                    "id",
                                                                                    record.getId())
                                                                            .execute()
                                                                            .then(
                                                                                    apply(
                                                                                            record,
                                                                                            rows,
                                                                                            auth)))
                                                    .onErrorResume(
                                                            failure ->
                                                                    failed(record, failure, auth)
                                                                            .then(
                                                                                    Mono.error(
                                                                                            failure)));
                                        }));
    }

    private Mono<byte[]> read(FilePart file) {
        return Mono.defer(
                () -> {
                    if (file == null
                            || file.filename().length() > 255
                            || !file.filename()
                                    .toLowerCase(Locale.ROOT)
                                    .matches(".*\\.(xlsx|xls)$"))
                        return Mono.error(error("subject_excel_invalid", 400));
                    return DataBufferUtils.join(file.content(), MAX_BYTES)
                            .switchIfEmpty(Mono.error(error("subject_excel_invalid", 400)))
                            .map(
                                    buffer -> {
                                        try {
                                            var bytes = new byte[buffer.readableByteCount()];
                                            buffer.read(bytes);
                                            return bytes;
                                        } finally {
                                            DataBufferUtils.release(buffer);
                                        }
                                    })
                            .onErrorMap(
                                    DataBufferLimitException.class,
                                    failure -> error("subject_excel_invalid", 400));
                });
    }

    private Mono<ImportResult> apply(
            SubjectImportEntity record, List<ImportRow> rows, Authentication auth) {
        return validateExisting(rows)
                .thenMany(Flux.fromIterable(rows))
                .concatMap(
                        row ->
                                subjects.create(row.input())
                                        .onErrorMap(
                                                failure ->
                                                        new BusinessException.NoStackTrace(
                                                                "error.property_finance_subject_excel_row",
                                                                "error.property_finance_subject_excel_row",
                                                                400,
                                                                row.number(),
                                                                "收费科目名称",
                                                                failure
                                                                                instanceof
                                                                                BusinessException
                                                                                                business
                                                                        ? org.hswebframework.web
                                                                                .i18n.LocaleUtils
                                                                                .resolveMessage(
                                                                                        business
                                                                                                .getMessage(),
                                                                                        business
                                                                                                .getMessage(),
                                                                                        business
                                                                                                .getArgs())
                                                                        : org.hswebframework.web
                                                                                .i18n.LocaleUtils
                                                                                .resolveMessage(
                                                                                        "error.property_finance_subject_import_failed",
                                                                                        "error.property_finance_subject_import_failed"))))
                .then(
                        audit(records.createUpdate(), auth, 0)
                                .set("status", "SUCCESS")
                                .set("totalCount", rows.size())
                                .set("successCount", rows.size())
                                .set("errorCount", 0)
                                .set("finishTime", System.currentTimeMillis())
                                .where("id", record.getId())
                                .execute())
                .thenReturn(new ImportResult(record.getId(), rows.size()))
                .as(TransactionalOperator.create(transactionManager)::transactional);
    }

    private Mono<Void> failed(SubjectImportEntity record, Throwable failure, Authentication auth) {
        var message =
                failure instanceof BusinessException business
                        ? org.hswebframework.web.i18n.LocaleUtils.resolveMessage(
                                business.getMessage(),
                                business.getMessage() + " " + Arrays.toString(business.getArgs()),
                                business.getArgs())
                        : org.hswebframework.web.i18n.LocaleUtils.resolveMessage(
                                "error.property_finance_subject_import_failed",
                                "error.property_finance_subject_import_failed");
        var update =
                audit(records.createUpdate(), auth, 0)
                        .set("status", "FAILED")
                        .set("successCount", 0)
                        .set("errorCount", 1)
                        .set(
                                "errorMessage",
                                message.length() > 8000
                                        ? message.substring(0, 7900)
                                                + org.hswebframework.web.i18n.LocaleUtils
                                                        .resolveMessage(
                                                                "error.property_finance_subject_import_truncated",
                                                                "error.property_finance_subject_import_truncated")
                                        : message)
                        .set("finishTime", System.currentTimeMillis());
        if (failure instanceof BusinessException business
                && business.getArgs() != null
                && business.getArgs().length > 3
                && business.getArgs()[3] instanceof Integer total) update.set("totalCount", total);
        return update.where("id", record.getId()).execute().then();
    }

    public Mono<PagerResult<SubjectImportEntity>> records(QueryParamEntity input) {
        return scope.authenticated()
                .flatMap(
                        auth -> {
                            var query = SubjectService.page(input);
                            query.toNestQuery(nested -> nested.and("deleted", false));
                            query.toQuery().orderByDesc("createTime");
                            return QueryHelper.queryPager(query, records::createQuery);
                        });
    }

    private Mono<Void> validateExisting(List<ImportRow> rows) {
        return Flux.fromIterable(rows)
                .concatMap(
                        row ->
                                subjectRows
                                        .createQuery()
                                        .where("categoryId", row.input().getCategoryId())
                                        .and("nameKey", row.input().getName())
                                        .and("deleted", false)
                                        .count()
                                        .flatMap(
                                                count ->
                                                        count == 0
                                                                ? Mono.empty()
                                                                : Mono.error(
                                                                        rowError(
                                                                                row.number(),
                                                                                HEADERS.get(1)))))
                .then();
    }

    private Mono<References> references() {
        return scope.authenticated()
                .then(
                        Mono.zip(
                                categories.list().collectList(),
                                arrears.options().collectList(),
                                dictionaries.options("charge-item-in-type")))
                .map(tuple -> new References(tuple.getT1(), tuple.getT2(), tuple.getT3()));
    }

    private static byte[] template(References references) throws IOException {
        try (var workbook = new XSSFWorkbook();
                var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("收费科目");
            var header = sheet.createRow(0);
            var style = workbook.createCellStyle();
            var font = workbook.createFont();
            font.setBold(true);
            style.setFont(font);
            style.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            for (int index = 0; index < HEADERS.size(); index++) {
                var cell = header.createCell(index);
                cell.setCellValue(HEADERS.get(index));
                cell.setCellStyle(style);
                sheet.setColumnWidth(index, (index == 0 ? 48 : 25) * 256);
            }
            sheet.createFreezePane(0, 1);
            var reference = workbook.createSheet("选项参考");
            var values =
                    List.of(
                            paths(references.categories()).keySet().stream().sorted().toList(),
                            references.types().stream()
                                    .map(PropertyDictionaryOption::text)
                                    .toList(),
                            List.of("是", "否"),
                            List.of("是", "否"),
                            references.configs().stream()
                                    .map(ArrearsConfigEntity::getName)
                                    .toList());
            var columns = List.of(0, 2, 3, 4, 5);
            for (int index = 0; index < values.size(); index++) {
                var list = values.get(index);
                if (list.isEmpty()) continue;
                for (int row = 0; row < list.size(); row++) {
                    var target = reference.getRow(row);
                    if (target == null) target = reference.createRow(row);
                    target.createCell(index).setCellValue(list.get(row));
                }
                var name = workbook.createName();
                name.setNameName("subject_options_" + index);
                String column = CellReference.convertNumToColString(index);
                name.setRefersToFormula("'选项参考'!$" + column + "$1:$" + column + "$" + list.size());
                var helper = sheet.getDataValidationHelper();
                var validation =
                        helper.createValidation(
                                helper.createFormulaListConstraint(name.getNameName()),
                                new CellRangeAddressList(
                                        1, MAX_ROWS, columns.get(index), columns.get(index)));
                validation.setShowErrorBox(true);
                validation.setErrorStyle(DataValidation.ErrorStyle.STOP);
                sheet.addValidationData(validation);
            }
            var guide = workbook.createSheet("填写说明");
            guide.createRow(0)
                    .createCell(0)
                    .setCellValue("最多500行。选择已有收费目录完整路径、费用类型与欠费通知等级；是/否必填。编码由系统生成。任何一行错误整批不保存。");
            guide.setColumnWidth(0, 110 * 256);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static List<ImportRow> parse(byte[] bytes, References references) {
        int total = 0;
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            total = sheet.getLastRowNum();
            if (total < 1 || total > MAX_ROWS) throw error("subject_excel_invalid", 400);
            for (int column = 0; column < HEADERS.size(); column++)
                if (!HEADERS.get(column).equals(text(sheet.getRow(0), column, 1)))
                    throw error("subject_excel_invalid", 400);
            var paths = paths(references.categories());
            var result = new ArrayList<ImportRow>();
            var names = new HashSet<String>();
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                var row = sheet.getRow(index);
                int number = index + 1;
                var input = new SubjectInput();
                var category = paths.get(text(row, 0, number));
                if (category == null) throw rowError(number, HEADERS.get(0));
                input.setCategoryId(category);
                var name = text(row, 1, number);
                if (name.isBlank() || name.length() > 100 || !names.add(category + "/" + name))
                    throw rowError(number, HEADERS.get(1));
                input.setName(name);
                String fee = text(row, 2, number);
                var matchingTypes =
                        references.types().stream()
                                .filter(
                                        option ->
                                                option.text().equals(fee)
                                                        || option.value().equals(fee))
                                .toList();
                if (matchingTypes.size() != 1) throw rowError(number, HEADERS.get(2));
                input.setFeeType(matchingTypes.get(0).value());
                input.setEnabled(flag(text(row, 3, number), number, 3));
                input.setAutoInvoice(flag(text(row, 4, number), number, 4));
                String config = text(row, 5, number);
                input.setArrearsConfigId(
                        references.configs().stream()
                                .filter(option -> option.getName().equals(config))
                                .map(ArrearsConfigEntity::getId)
                                .findFirst()
                                .orElseThrow(() -> rowError(number, HEADERS.get(5))));
                var remark = text(row, 6, number);
                if (remark.length() > 250) throw rowError(number, HEADERS.get(6));
                input.setRemark(remark);
                if (row.getLastCellNum() > HEADERS.size())
                    for (int column = HEADERS.size(); column < row.getLastCellNum(); column++)
                        if (!text(row, column, number).isEmpty())
                            throw error("subject_excel_invalid", 400);
                result.add(new ImportRow(number, input));
            }
            return result;
        } catch (BusinessException failure) {
            if (failure.getArgs() != null && failure.getArgs().length == 3) {
                var args = Arrays.copyOf(failure.getArgs(), 4);
                args[3] = total;
                throw new BusinessException.NoStackTrace(
                        failure.getMessage(), failure.getMessage(), failure.getStatus(), args);
            }
            throw new BusinessException.NoStackTrace(
                    failure.getMessage(),
                    failure.getMessage(),
                    failure.getStatus(),
                    0,
                    "",
                    failure.getMessage(),
                    total);
        } catch (Exception failure) {
            throw error("subject_excel_invalid", 400);
        }
    }

    private static String text(Row row, int column, int number) {
        if (row == null) throw rowError(number, HEADERS.get(Math.min(column, HEADERS.size() - 1)));
        var cell = row.getCell(column);
        if (cell == null) return "";
        if (cell.getCellType() == CellType.FORMULA || cell.getCellType() == CellType.ERROR)
            throw rowError(number, HEADERS.get(Math.min(column, HEADERS.size() - 1)));
        return new DataFormatter(Locale.ROOT).formatCellValue(cell).trim();
    }

    private static boolean flag(String value, int row, int column) {
        if ("是".equals(value)) return true;
        if ("否".equals(value)) return false;
        throw rowError(row, HEADERS.get(column));
    }

    private static Map<String, String> paths(List<SubjectCategoryEntity> categories) {
        var rows = new HashMap<String, SubjectCategoryEntity>();
        for (var category : categories) rows.put(category.getId(), category);
        var result = new LinkedHashMap<String, String>();
        for (var category : categories) {
            var parts = new ArrayDeque<String>();
            var current = category;
            var seen = new HashSet<String>();
            while (current != null) {
                if (!seen.add(current.getId())) throw error("subject_excel_invalid", 400);
                parts.addFirst(current.getName());
                current = rows.get(current.getParentId());
            }
            var path = String.join("/", parts);
            if (result.put(path, category.getId()) != null)
                throw error("subject_excel_invalid", 400);
        }
        return result;
    }

    private static BusinessException rowError(int number, String field) {
        return new BusinessException.NoStackTrace(
                "error.property_finance_subject_excel_row",
                "error.property_finance_subject_excel_row",
                400,
                number,
                field,
                org.hswebframework.web.i18n.LocaleUtils.resolveMessage(
                        "error.property_finance_subject_excel_cell",
                        "error.property_finance_subject_excel_cell"));
    }

    private record References(
            List<SubjectCategoryEntity> categories,
            List<ArrearsConfigEntity> configs,
            List<PropertyDictionaryOption> types) {}

    private record ImportRow(int number, SubjectInput input) {}

    public record ImportResult(String recordId, long count) {}
}
