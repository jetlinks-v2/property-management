package org.jetlinks.pro.property.base.service;

import static org.jetlinks.pro.property.base.excel.ArchiveExcel.*;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.bean.FastBeanCopier;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.customer.*;
import org.jetlinks.pro.property.base.entity.*;
import org.jetlinks.pro.property.base.excel.ArchiveExcel;
import org.jetlinks.pro.property.base.excel.ArchiveExcel.Document;
import org.jetlinks.pro.property.base.excel.ArchiveExcel.SheetData;
import org.jetlinks.pro.property.base.excel.ArchiveImportSchema;
import org.springframework.core.io.buffer.*;
import org.springframework.http.*;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 文件接口编排：解析及转换不写库，领域服务整批预检后在一个响应式事务中写入。 */
@Service
@RequiredArgsConstructor
public class ArchiveExcelService {
    private final ArchiveScope scope;
    private final PropertyHouseService houses;
    private final PropertyCustomerService customers;
    private final PropertyUserService users;
    private final PropertyDictionaryService dictionaries;
    private static final Map<String, String> DICTIONARY_FIELDS =
            Map.of(
                    "houseType",
                    "house-type",
                    "houseState",
                    "house-state",
                    "nature",
                    "customer-nature",
                    "certificateType",
                    "certificate-type");
    private static final Map<String, String> DICTIONARY_NAMES =
            Map.of(
                    "house-type",
                    "房屋类型",
                    "house-state",
                    "房态",
                    "customer-nature",
                    "客户性质",
                    "certificate-type",
                    "证件类型");

    public Mono<Document> template(String projectId, String type) {
        var columns = ArchiveImportSchema.columns(type);
        boolean customer = !"house".equals(type);
        return scope.project(projectId, CrudAssetPermission.save)
                .flatMap(
                        project ->
                                Mono.zip(
                                                houses.export(
                                                        projectId, null, new QueryParamEntity()),
                                                dictionaryItems(true),
                                                customer
                                                        ? users.customerReferences()
                                                        : Mono.just(List.<PropertyUserEntity>of()))
                                        .map(
                                                tuple ->
                                                        templateDocument(
                                                                project.getName(),
                                                                type,
                                                                columns,
                                                                tuple.getT1(),
                                                                tuple.getT2(),
                                                                tuple.getT3())));
    }

    private Document templateDocument(
            String project,
            String type,
            List<String> columns,
            List<PropertyHouseService.HouseView> spaces,
            List<PropertyDictionaryItemEntity> items,
            List<PropertyUserEntity> users) {
        boolean customer = !"house".equals(type);
        if (spaces.isEmpty()) throw failure("no_spaces");
        var available =
                spaces.stream()
                        .filter(h -> !customer || Boolean.TRUE.equals(h.getCompleted()))
                        .toList();
        if (customer && available.isEmpty()) throw failure("no_houses");
        var instructions = new ArrayList<Map<String, Object>>();
        instructions.add(Map.of("field", "所属项目", "description", project));
        instructions.add(
                Map.of(
                        "field",
                        "模板类型",
                        "description",
                        "house".equals(type) ? "空间资料" : "person".equals(type) ? "个人客户" : "企业客户"));
        instructions.add(
                Map.of(
                        "field",
                        "导入规则",
                        "description",
                        "红色表头为必填项。仅填写第一张“导入数据”表；“填写示例”表仅供参考，不参与导入。建议每次不超过1000条，文件不超过10MB；整份校验通过后才保存，错误会提示行号。不保留中间空行，不修改表头。电话、证件按文本填写。"));
        instructions.add(
                Map.of(
                        "field",
                        "匹配规则",
                        "description",
                        "手动填写完整空间路径，精确匹配当前项目内空间；客户只能关联已完善资料的空间；客户按同步用户名称匹配，重名再用电话区分。不存在或仍重名时拒绝；不创建空间或SaaS账号。"));
        instructions.add(
                Map.of(
                        "field",
                        customer ? "客户资料" : "空间资料",
                        "description",
                        customer
                                ? "已有客户只新增当前空间记录，不覆盖资料。同账号多行主档资料须一致。每条记录的开始时间由系统在保存时自动记录，无需填写。"
                                : "仅补充未完善的空间资料，修改已有资料请使用界面编辑。空间路径手动填写，空间参考保留本项目各层级空间。"));
        for (var key : columns)
            instructions.add(Map.of("field", header(key), "description", guide(key)));
        var references = new ArrayList<SheetData>();
        references.add(new SheetData("填写说明", List.of("field", "description"), instructions));
        references.add(
                new SheetData(
                        "空间参考",
                        List.of("spacePath", "completed"),
                        spaces.stream().map(this::spaceRow).toList()));
        var dictionaryRows = new ArrayList<Map<String, Object>>();
        Map<String, List<String>> options = new LinkedHashMap<>();
        DICTIONARY_FIELDS.forEach(
                (field, code) -> {
                    if (!columns.contains(field)) return;
                    var values =
                            items.stream()
                                    .filter(item -> code.equals(item.getTypeCode()))
                                    .map(PropertyDictionaryItemEntity::getText)
                                    .distinct()
                                    .toList();
                    options.put(field, values);
                    for (var value : values)
                        dictionaryRows.add(
                                Map.of("typeCode", DICTIONARY_NAMES.get(code), "text", value));
                });
        references.add(new SheetData("字典参考", List.of("typeCode", "text"), dictionaryRows));
        if (customer) {
            var userRows = new ArrayList<Map<String, Object>>();
            for (var user : users) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put(
                        "name",
                        user.getName() == null || user.getName().isBlank()
                                ? user.getUsername()
                                : user.getName());
                row.put("username", user.getUsername());
                row.put("telephone", user.getTelephone());
                row.put("email", user.getEmail());
                userRows.add(row);
            }
            references.add(
                    new SheetData(
                            "用户参考", List.of("name", "username", "telephone", "email"), userRows));
        }
        // 示例单独成表，填写页不预填路径，避免示例或无意保留的空资料行进入导入。
        references.add(new SheetData("填写示例", columns, List.of(example(type, available, options))));
        String label = "house".equals(type) ? "空间资料" : "person".equals(type) ? "个人客户" : "企业客户";
        return new Document(
                project + "-" + label + "-下载模板.xlsx",
                new SheetData("导入数据", columns, List.of()),
                references,
                options);
    }

    private Map<String, Object> example(
            String type,
            List<PropertyHouseService.HouseView> spaces,
            Map<String, List<String>> options) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("spacePath", spaces.get(0).getFullName());
        if ("house".equals(type)) {
            row.put("houseType", options.get("houseType").stream().findFirst().orElse("住宅"));
            row.put("houseState", options.get("houseState").stream().findFirst().orElse("自住"));
            row.put("billingArea", "100");
            row.put("buildingArea", "110");
            row.put("insideArea", "90");
            row.put("deliveryDate", "2026-01-01");
            row.put("remark", "仅作填写示例");
        } else {
            row.put(
                    "person".equals(type) ? "personName" : "companyName",
                    "person".equals(type) ? "张三（示例，请替换）" : "示例公司（请替换）");
            row.put("telephone", "13800138000");
            row.put("email", "sample@example.com");
            row.put("address", "示例联系地址");
            if ("person".equals(type)) {
                row.put("nature", options.get("nature").stream().findFirst().orElse("业主"));
                row.put(
                        "certificateType",
                        options.get("certificateType").stream().findFirst().orElse("身份证"));
            } else {
                row.put("legalRepresentative", "张三");
                row.put("registeredAddress", "示例注册地址");
            }
        }
        return row;
    }

    public Mono<PropertyHouseService.ImportResult> importHouses(String projectId, FilePart file) {
        return scope.project(projectId, CrudAssetPermission.save)
                .then(readFile(file, "house"))
                .flatMap(
                        rows ->
                                Mono.zip(
                                                dictionaryItems(true),
                                                houses.export(
                                                        projectId, null, new QueryParamEntity()))
                                        .map(
                                                tuple ->
                                                        houseRows(
                                                                ArchiveImportMatcher.resolve(
                                                                        rows,
                                                                        "house",
                                                                        tuple.getT2(),
                                                                        List.of()),
                                                                tuple.getT1())))
                .flatMap(rows -> houses.importRows(projectId, rows));
    }

    private List<PropertyHouseService.HouseWrite> houseRows(
            List<Map<String, Object>> rows, List<PropertyDictionaryItemEntity> items) {
        var result = new ArrayList<PropertyHouseService.HouseWrite>();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            int line = i + 2;
            result.add(
                    new PropertyHouseService.HouseWrite(
                            0L,
                            dictionary(row, "houseType", line, true, items),
                            dictionary(row, "houseState", line, true, items),
                            decimal(row, "billingArea", line, true),
                            decimal(row, "buildingArea", line, false),
                            decimal(row, "insideArea", line, false),
                            date(row, "deliveryDate", line, false),
                            text(row, "remark", line, false),
                            text(row, "spaceId", line, true)));
        }
        return result;
    }

    public Mono<CustomerRequests.ImportResult> importCustomers(
            String projectId, String type, FilePart file) {
        return scope.project(projectId, CrudAssetPermission.save)
                .then(Mono.defer(() -> readFile(file, ArchiveImportSchema.customerType(type))))
                .flatMap(
                        rows ->
                                Mono.zip(
                                                dictionaryItems(true),
                                                houses.export(
                                                        projectId, null, new QueryParamEntity()),
                                                users.customerReferences())
                                        .map(
                                                tuple ->
                                                        customerRows(
                                                                ArchiveImportMatcher.resolve(
                                                                        rows,
                                                                        type,
                                                                        tuple.getT2(),
                                                                        tuple.getT3()),
                                                                tuple.getT1())))
                .flatMap(rows -> customers.importCustomers(projectId, rows));
    }

    private List<CustomerRequests.Create> customerRows(
            List<Map<String, Object>> rows, List<PropertyDictionaryItemEntity> items) {
        var result = new ArrayList<CustomerRequests.Create>();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            int line = i + 2;
            var request = FastBeanCopier.copy(row, new CustomerRequests.Create());
            request.setUserId(text(row, "userId", line, true));
            request.setSpaceId(text(row, "spaceId", line, true));
            request.setName(text(row, "name", line, true));
            request.setTelephone(text(row, "telephone", line, true));
            request.setCustomerType(
                    alias(
                            text(row, "customerType", line, true),
                            Map.of(
                                    "个人", "person", "企业", "company", "个人客户", "person", "企业客户",
                                    "company")));
            request.setCertificateType(dictionary(row, "certificateType", line, false, items));
            request.setNature(
                    dictionary(
                            row,
                            "nature",
                            line,
                            "person".equals(request.getCustomerType()),
                            items));
            result.add(request);
        }
        return result;
    }

    private Mono<List<Map<String, Object>>> readFile(FilePart file, String type) {
        return Mono.defer(
                () -> {
                    if (file == null
                            || !file.filename()
                                    .toLowerCase(Locale.ROOT)
                                    .matches(".*\\.(xlsx|xls)$"))
                        return Mono.error(failure("format"));
                    return DataBufferUtils.join(file.content(), MAX_BYTES)
                            .switchIfEmpty(Mono.error(failure("file")))
                            .map(
                                    buffer -> {
                                        try {
                                            byte[] bytes = new byte[buffer.readableByteCount()];
                                            buffer.read(bytes);
                                            return bytes;
                                        } finally {
                                            DataBufferUtils.release(buffer);
                                        }
                                    })
                            .onErrorMap(DataBufferLimitException.class, error -> failure("size"))
                            .flatMap(
                                    bytes ->
                                            Mono.fromCallable(
                                                            () ->
                                                                    ArchiveExcel.read(
                                                                            bytes,
                                                                            ArchiveImportSchema
                                                                                    .columns(type)))
                                                    .subscribeOn(Schedulers.boundedElastic()));
                });
    }

    public Mono<Document> exportHouses(String projectId, String spaceId, QueryParamEntity query) {
        return Mono.zip(houses.export(projectId, spaceId, query), dictionaryItems(false))
                .map(
                        tuple -> {
                            var rows =
                                    tuple.getT1().stream()
                                            .map(
                                                    house -> {
                                                        var row =
                                                                new LinkedHashMap<String, Object>(
                                                                        FastBeanCopier.copy(
                                                                                house,
                                                                                new HashMap<
                                                                                        String,
                                                                                        Object>()));
                                                        row.putAll(spaceRow(house));
                                                        display(row, tuple.getT2());
                                                        return (Map<String, Object>) row;
                                                    })
                                            .toList();
                            return new Document(
                                    "房屋资料.xlsx",
                                    new SheetData(
                                            "房屋资料",
                                            concat(columns(false), List.of("completed")).stream()
                                                    .filter(column -> !"spaceId".equals(column)
                                                            && !"spaceName".equals(column))
                                                    .toList(),
                                            rows),
                                    List.of());
                        });
    }

    public Mono<Document> exportCustomers(
            String projectId, String spaceId, QueryParamEntity query, boolean history) {
        Mono<List<RelationshipView>> links =
                history
                        ? customers.exportHistory(projectId, spaceId, query)
                        : customers
                                .export(projectId, spaceId, query)
                                .map(
                                        list -> {
                                            var rows = new ArrayList<RelationshipView>();
                                            for (var customer : list) {
                                                if (customer.getRelationships().isEmpty()) {
                                                    var empty = new RelationshipView();
                                                    empty.setCustomer(customer);
                                                    rows.add(empty);
                                                } else
                                                    for (var link : customer.getRelationships()) {
                                                        link.setCustomer(customer);
                                                        rows.add(link);
                                                    }
                                            }
                                            if (rows.size() > 10_000)
                                                throw failure("reference_limit");
                                            return rows;
                                        });
        return Mono.zip(links, dictionaryItems(false))
                .map(
                        tuple -> {
                            var rows =
                                    tuple.getT1().stream()
                                            .map(
                                                    link -> {
                                                        var row =
                                                                FastBeanCopier.copy(
                                                                        link.getCustomer(),
                                                                        new HashMap<
                                                                                String, Object>());
                                                        row.putAll(
                                                                FastBeanCopier.copy(
                                                                        link,
                                                                        new HashMap<
                                                                                String, Object>()));
                                                        row.put(
                                                                "customerId",
                                                                link.getCustomer() == null
                                                                        ? null
                                                                        : link.getCustomer()
                                                                                .getId());
                                                        row.put("relationshipId", link.getId());
                                                        row.put("spaceName", link.getSpaceName());
                                                        display(row, tuple.getT2());
                                                        return (Map<String, Object>) row;
                                                    })
                                            .toList();
                            return new Document(
                                    history ? "历史客户.xlsx" : "客户资料.xlsx",
                                    new SheetData(
                                            history ? "历史客户" : "客户资料",
                                            concat(
                                                    columns(true),
                                                    List.of(
                                                            "customerId",
                                                            "relationshipId",
                                                            "endTime",
                                                            "endReason")),
                                            rows),
                                    List.of());
                        });
    }

    private Mono<List<PropertyDictionaryItemEntity>> dictionaryItems(boolean active) {
        return dictionaries
                .types()
                .flatMapMany(Flux::fromIterable)
                .filter(
                        type ->
                                DICTIONARY_NAMES.containsKey(type.getCode())
                                        && (!active || Boolean.TRUE.equals(type.getEnabled())))
                .concatMap(
                        type -> dictionaries.items(type.getCode()).flatMapMany(Flux::fromIterable))
                .filter(item -> !active || Boolean.TRUE.equals(item.getEnabled()))
                .collectList();
    }

    private String dictionary(
            Map<String, Object> row,
            String field,
            int line,
            boolean required,
            List<PropertyDictionaryItemEntity> items) {
        String value = text(row, field, line, required);
        if (value == null) return null;
        var options =
                items.stream()
                        .filter(item -> DICTIONARY_FIELDS.get(field).equals(item.getTypeCode()))
                        .toList();
        if (options.stream().anyMatch(item -> item.getValue().equals(value))) return value;
        var matches = options.stream().filter(item -> item.getText().equals(value)).toList();
        if (matches.size() != 1) throw cell(line, field);
        return matches.get(0).getValue();
    }

    private Map<String, Object> spaceRow(PropertyHouseService.HouseView house) {
        var row = new LinkedHashMap<String, Object>();
        row.put("spaceId", house.getSpaceId());
        row.put("spaceName", house.getName());
        row.put("fullName", house.getFullName());
        row.put("spacePath", house.getFullName());
        row.put("completed", Boolean.TRUE.equals(house.getCompleted()) ? "已完善" : "待完善");
        return row;
    }

    private void display(Map<String, Object> row, List<PropertyDictionaryItemEntity> items) {
        DICTIONARY_FIELDS.forEach(
                (field, code) -> {
                    var value = row.get(field);
                    if (value != null)
                        row.put(
                                field,
                                items.stream()
                                        .filter(
                                                item ->
                                                        code.equals(item.getTypeCode())
                                                                && value.equals(item.getValue()))
                                        .map(PropertyDictionaryItemEntity::getText)
                                        .findFirst()
                                        .orElse(value.toString()));
                });
        if (row.get("customerType") != null)
            row.put("customerType", "person".equals(row.get("customerType")) ? "个人" : "企业");
        for (String field : List.of("deliveryDate", "startTime", "endTime")) {
            var time = row.get(field);
            if (time instanceof Number number)
                row.put(
                        field,
                        Instant.ofEpochMilli(number.longValue())
                                .atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }
    }

    private static String alias(String value, Map<String, String> aliases) {
        return value == null ? null : aliases.getOrDefault(value, value);
    }

    private static List<String> concat(List<String> first, List<String> second) {
        var columns = new ArrayList<>(first);
        columns.addAll(second);
        return columns;
    }

    public Mono<Void> write(Document document, ServerHttpResponse response) {
        response.getHeaders()
                .setContentType(
                        MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        response.getHeaders()
                .setContentDisposition(
                        ContentDisposition.attachment()
                                .filename(document.filename(), StandardCharsets.UTF_8)
                                .build());
        response.getHeaders().setCacheControl("no-store");
        return response.writeWith(ArchiveExcel.write(document));
    }
}
