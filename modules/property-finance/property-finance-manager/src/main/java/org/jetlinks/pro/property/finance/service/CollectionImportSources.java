package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.archive.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.*;
import org.springframework.stereotype.Component;

import reactor.core.publisher.*;

import java.time.LocalDate;
import java.util.*;

/** 人工收款与导入共用现有预览/执行入口，禁止直接更新应收已收或资金余额。 */
@Component
@RequiredArgsConstructor
public class CollectionImportSources {
    private final HouseArchiveSource archive;
    private final CashierService cashier;
    private final FundsService funds;
    private final TemporaryService temporary;
    private final ReactiveRepository<ReceivableEntity, String> bills;
    private final ReactiveRepository<StandardEntity, String> standards;
    private final ReactiveRepository<SubjectEntity, String> subjects;
    private final ReactiveRepository<ProjectSubjectEntity, String> issued;

    public Mono<Prepared> prepare(
            String project, String kind, CollectionImportInput source, int row) {
        var input = JSON.parseObject(JSON.toJSONString(source), CollectionImportInput.class);
        CollectionImportWorkbook.validate(kind, input, row);
        return Mono.defer(
                        () ->
                                "RECEIVED".equals(kind)
                                        ? received(project, input, row)
                                        : resolveHouse(project, input, row)
                                                .then(resolveStandard(project, input, row))
                                                .then(resolveCompany(project, input, row))
                                                .then(
                                                        Mono.defer(
                                                                () ->
                                                                        previewOther(
                                                                                project, kind,
                                                                                input))))
                .onErrorMap(
                        org.hswebframework.web.exception.BusinessException.class,
                        e -> {
                            if ("error.property_finance_collection_row".equals(e.getI18nCode()))
                                return e;
                            var suffix = e.getI18nCode().replace("error.property_finance_", "");
                            var field =
                                    suffix.contains("period")
                                            ? "收款时间"
                                            : suffix.contains("company")
                                                    ? "收款公司"
                                                    : suffix.contains("standard")
                                                            ? "收费标准"
                                                            : suffix.contains("subject")
                                                                    ? "收费科目"
                                                                    : suffix.contains("customer")
                                                                            ? "客户名称"
                                                                            : suffix.contains(
                                                                                            "house")
                                                                                    ? "房屋完整路径"
                                                                                    : suffix
                                                                                                            .contains(
                                                                                                                    "recorded")
                                                                                                    || suffix
                                                                                                            .contains(
                                                                                                                    "method")
                                                                                            ? "支付方式/入账银行"
                                                                                            : "收款金额/业务引用";
                            return CollectionImportWorkbook.rowError(row, field, suffix);
                        });
    }

    private Mono<Void> resolveCompany(String project, CollectionImportInput i, int row) {
        return (i.getCompanyId() == null && i.getCompanyName() == null && i.getStandardId() == null
                        ? funds.defaultCompanyId(project).doOnNext(i::setCompanyId).then()
                        : Mono.<Void>empty())
                .then(cashier.options(project))
                .flatMap(
                        options -> {
                            var matches =
                                    options.stream()
                                            .filter(
                                                    c ->
                                                            i.getCompanyId() != null
                                                                    ? c.id().equals(
                                                                                    i
                                                                                            .getCompanyId())
                                                                    : i.getCompanyName() == null
                                                                            || c.name()
                                                                                    .equals(
                                                                                            i
                                                                                                    .getCompanyName()))
                                            .toList();
                            if (matches.size() != 1)
                                return Mono.error(
                                        CollectionImportWorkbook.rowError(
                                                row, "收款公司", "collection_match_invalid"));
                            var company = matches.get(0);
                            i.setCompanyId(company.id());
                            i.setCompanyName(company.name());
                            var funding =
                                    company.funding().stream()
                                            .filter(
                                                    f ->
                                                            i.getMethod() != null
                                                                    ? f.method()
                                                                                    .equals(
                                                                                            i
                                                                                                    .getMethod())
                                                                            && Objects.equals(
                                                                                    f.recordedId(),
                                                                                    i
                                                                                            .getRecordedId())
                                                                    : (paymentLabel(f)
                                                                                            .equals(
                                                                                                    i
                                                                                                            .getMethodText())
                                                                                    || f.text()
                                                                                            .equals(
                                                                                                    i
                                                                                                            .getMethodText()))
                                                                            && (i.getBankName()
                                                                                            == null
                                                                                    || i.getBankName()
                                                                                            .isBlank()
                                                                                    || Objects
                                                                                            .equals(
                                                                                                    i
                                                                                                            .getBankName(),
                                                                                                    f
                                                                                                            .bankName())))
                                            .toList();
                            if (funding.size() != 1)
                                return Mono.error(
                                        CollectionImportWorkbook.rowError(
                                                row, "支付方式/入账银行", "collection_match_invalid"));
                            var method = funding.get(0);
                            i.setMethod(method.method());
                            i.setRecordedId(method.recordedId());
                            i.setMethodText(paymentLabel(method));
                            i.setBankName(method.bankName());
                            return Mono.empty();
                        });
    }

    private Mono<Void> resolveHouse(String project, CollectionImportInput i, int row) {
        Mono<String> id =
                i.getSpaceId() != null
                        ? Mono.just(i.getSpaceId())
                        : archive.houses(project, null, i.getFullName(), 0, 200)
                                .flatMap(
                                        page -> {
                                            if (page.total() > 200)
                                                return Mono.error(
                                                        CollectionImportWorkbook.rowError(
                                                                row,
                                                                "房屋完整路径",
                                                                "collection_match_invalid"));
                                            var matches =
                                                    page.data().stream()
                                                            .filter(
                                                                    h ->
                                                                            h.fullName()
                                                                                    .equals(
                                                                                            i
                                                                                                    .getFullName()))
                                                            .toList();
                                            return matches.size() == 1
                                                    ? Mono.just(matches.get(0).spaceId())
                                                    : Mono.error(
                                                            CollectionImportWorkbook.rowError(
                                                                    row,
                                                                    "房屋完整路径",
                                                                    "collection_match_invalid"));
                                        });
        return id.flatMap(
                        space ->
                                AssetsHolder.assertPermission(
                                                AssetType.of("space"),
                                                space,
                                                CrudAssetPermission.save)
                                        .then(archive.context(project, space, false)))
                .flatMap(
                        context -> {
                            var customers =
                                    context.customers().stream()
                                            .filter(
                                                    c ->
                                                            i.getRelationshipId() != null
                                                                    ? c.relationshipId()
                                                                            .equals(
                                                                                    i
                                                                                            .getRelationshipId())
                                                                    : c.name()
                                                                            .equals(
                                                                                    i
                                                                                            .getCustomerName()))
                                            .toList();
                            if (customers.size() != 1)
                                return Mono.error(
                                        CollectionImportWorkbook.rowError(
                                                row, "客户名称", "collection_match_invalid"));
                            i.setSpaceId(context.house().spaceId());
                            i.setFullName(context.house().fullName());
                            i.setRelationshipId(customers.get(0).relationshipId());
                            i.setCustomerName(customers.get(0).name());
                            return Mono.empty();
                        });
    }

    private Mono<Void> resolveStandard(String project, CollectionImportInput i, int row) {
        if (i.getStandardId() != null
                || i.getStandardName() != null && !i.getStandardName().isBlank()) {
            var query = standards.createQuery().where("projectId", project).and("deleted", false);
            if (i.getStandardId() != null) query.and("id", i.getStandardId());
            else {
                query.and("standardName", i.getStandardName());
                if (i.getSubjectName() != null) query.and("subjectName", i.getSubjectName());
            }
            return query.fetch()
                    .take(2)
                    .collectList()
                    .flatMap(
                            matches -> {
                                if (matches.size() != 1)
                                    return Mono.error(
                                            CollectionImportWorkbook.rowError(
                                                    row, "收费科目/收费标准", "collection_match_invalid"));
                                var s = matches.get(0);
                                i.setStandardId(s.getId());
                                i.setStandardName(s.getStandardName());
                                if (i.getCompanyId() == null && i.getCompanyName() == null)
                                    i.setCompanyId(s.getCompanyId());
                                i.setSubjectId(s.getSubjectId());
                                i.setSubjectName(s.getSubjectName());
                                return Mono.empty();
                            });
        }
        return issued.createQuery()
                .where("projectId", project)
                .and("deleted", false)
                .fetch()
                .map(ProjectSubjectEntity::getSubjectId)
                .collectList()
                .flatMap(
                        ids -> {
                            if (ids.isEmpty())
                                return Mono.error(
                                        CollectionImportWorkbook.rowError(
                                                row, "收费科目", "collection_match_invalid"));
                            var query =
                                    subjects.createQuery().where("deleted", false).in("id", ids);
                            if (i.getSubjectId() != null) query.and("id", i.getSubjectId());
                            else query.and("name", i.getSubjectName());
                            return query.fetch()
                                    .take(2)
                                    .collectList()
                                    .flatMap(
                                            matches -> {
                                                if (matches.size() != 1)
                                                    return Mono.error(
                                                            CollectionImportWorkbook.rowError(
                                                                    row,
                                                                    "收费科目",
                                                                    "collection_match_invalid"));
                                                i.setSubjectId(matches.get(0).getId());
                                                i.setSubjectName(matches.get(0).getName());
                                                return Mono.empty();
                                            });
                        });
    }

    private Mono<Prepared> received(String project, CollectionImportInput i, int row) {
        return bill(project, i)
                .flatMap(
                        bill -> {
                            i.setSpaceId(bill.getSpaceId());
                            i.setFullName(bill.getFullName());
                            i.setCustomerName(bill.getCustomerName());
                            i.setRelationshipId(bill.getCustomerRelationshipId());
                            i.setStartDate(bill.getCalcStartDate());
                            i.setEndDate(bill.getCalcEndDate());
                            i.setAccountPeriod(bill.getAccountPeriod());
                            i.setSubjectId(bill.getSubjectId());
                            i.setSubjectName(bill.getSubjectName());
                            i.setStandardId(bill.getStandardId());
                            i.setStandardName(bill.getStandardName());
                            i.setReceivableId(bill.getId());
                            if (i.getPayerName() == null) i.setPayerName(bill.getCustomerName());
                            if (i.getCompanyId() == null && i.getCompanyName() == null)
                                i.setCompanyId(null);
                            return standards
                                    .findById(bill.getStandardId())
                                    .doOnNext(
                                            standard -> {
                                                if (i.getCompanyId() == null
                                                        && i.getCompanyName() == null)
                                                    i.setCompanyId(standard.getCompanyId());
                                            })
                                    .then(resolveCompany(project, i, row))
                                    .then(
                                            Mono.defer(
                                                    () ->
                                                            cashier.preview(
                                                                    project,
                                                                    cash(i, bill),
                                                                    LocalDate.now())))
                                    .thenReturn(new Prepared(i));
                        });
    }

    private Mono<ReceivableEntity> bill(String project, CollectionImportInput i) {
        var q = new QueryParamEntity();
        q.setPaging(true);
        q.setPageSize(2);
        var query =
                bills.createQuery().setParam(q).where("projectId", project).and("deleted", false);
        if (i.getReceivableId() != null) query.and("id", i.getReceivableId());
        else
            query.and("fullName", i.getFullName())
                    .and("customerName", i.getCustomerName())
                    .and("subjectName", i.getSubjectName())
                    .and("standardName", i.getStandardName())
                    .and("calcStartDate", i.getStartDate())
                    .and("calcEndDate", i.getEndDate());
        return query.fetch()
                .collectList()
                .flatMap(
                        rows -> {
                            if (rows.isEmpty())
                                return Mono.error(error("receivable_not_found", 404));
                            if (rows.size() != 1)
                                return Mono.error(error("collection_bill_ambiguous", 409));
                            var bill = rows.get(0);
                            return AssetsHolder.assertPermission(
                                            AssetType.of("space"),
                                            bill.getSpaceId(),
                                            CrudAssetPermission.save)
                                    .thenReturn(bill);
                        });
    }

    private static String paymentLabel(CashierConfiguration.FundingOption option) {
        return option.bankName() == null || option.bankName().isBlank()
                ? option.text()
                : option.text() + " · " + option.bankName();
    }

    private Mono<Prepared> previewOther(String project, String kind, CollectionImportInput i) {
        return "TEMPORARY".equals(kind)
                ? temporary.preview(project, temp(i), LocalDate.now()).thenReturn(new Prepared(i))
                : funds.preview(project, fund(kind, i), LocalDate.now())
                        .thenReturn(new Prepared(i));
    }

    /** 审批时重新取令牌并走原受控事务入口；稳定请求号与草稿状态共同避免重复入账。 */
    public Mono<String> publish(
            String project, String kind, String requestId, CollectionImportInput i) {
        if ("RECEIVED".equals(kind))
            return bill(project, i)
                    .flatMap(
                            b -> {
                                var input = cash(i, b);
                                return cashier.preview(project, input, LocalDate.now())
                                        .flatMap(
                                                p ->
                                                        cashier.collect(
                                                                project,
                                                                new CashierService.Submission(
                                                                        requestId, p.token(),
                                                                        input),
                                                                LocalDate.now()))
                                        .map(r -> r.payment().getId());
                            });
        if ("TEMPORARY".equals(kind)) {
            var input = temp(i);
            return temporary
                    .preview(project, input, LocalDate.now())
                    .flatMap(
                            p ->
                                    temporary.collect(
                                            project,
                                            new TemporaryService.Submission(
                                                    requestId, p.token(), input),
                                            LocalDate.now()))
                    .map(r -> r.payment().getId());
        }
        var input = fund(kind, i);
        return funds.preview(project, input, LocalDate.now())
                .flatMap(
                        p ->
                                funds.execute(
                                        project,
                                        new FundsService.Submission(requestId, p.token(), input),
                                        LocalDate.now()))
                .map(r -> r.entry().getId());
    }

    private CashierInput cash(CollectionImportInput i, ReceivableEntity bill) {
        var input = new CashierInput();
        input.setCompanyId(i.getCompanyId());
        input.setPayerName(i.getPayerName());
        input.setPayerRelationshipId(bill.getCustomerRelationshipId());
        input.setPaidAt(i.getPaidAt());
        input.setRemark(i.getRemark());
        input.setItems(
                List.of(new CashierInput.Item(bill.getId(), bill.getVersion(), i.getAmount())));
        input.setTenders(
                List.of(new CashierInput.Tender(i.getMethod(), i.getRecordedId(), i.getAmount())));
        if (i.getReductionAmount() != null && i.getReductionAmount().signum() > 0)
            input.setReduction(
                    new CashierInput.Reduction(
                            "AMOUNT", i.getReductionAmount(), i.getReductionReason()));
        return input;
    }

    private FundsInput fund(String kind, CollectionImportInput i) {
        var input = new FundsInput();
        input.setAction("TOPUP");
        input.setType(kind);
        input.setCompanyId(i.getCompanyId());
        input.setSpaceId(i.getSpaceId());
        input.setRelationshipId(i.getRelationshipId());
        input.setSubjectId(i.getSubjectId());
        input.setStandardId(i.getStandardId());
        input.setMethod(i.getMethod());
        input.setRecordedId(i.getRecordedId());
        input.setAmount(i.getAmount());
        input.setOccurredAt(i.getPaidAt());
        input.setPayerName(i.getPayerName());
        input.setReason(i.getRemark());
        input.setStartDate(i.getStartDate());
        input.setEndDate(i.getEndDate());
        input.setQuantity(i.getQuantity());
        return input;
    }

    private TemporaryInput temp(CollectionImportInput i) {
        var input = new TemporaryInput();
        input.setCompanyId(i.getCompanyId());
        input.setSpaceId(i.getSpaceId());
        input.setRelationshipId(i.getRelationshipId());
        input.setSubjectId(i.getSubjectId());
        input.setStandardId(i.getStandardId());
        input.setAmount(i.getAmount());
        input.setPaidAt(i.getPaidAt());
        input.setPayerName(i.getPayerName());
        input.setRemark(i.getRemark());
        input.setStartDate(i.getStartDate());
        input.setEndDate(i.getEndDate());
        input.setAccountPeriod(i.getAccountPeriod());
        input.setQuantity(i.getQuantity());
        input.setTenders(
                List.of(new CashierInput.Tender(i.getMethod(), i.getRecordedId(), i.getAmount())));
        return input;
    }

    public Mono<Map<String, List<List<String>>>> references(String project) {
        return references(project, "RECEIVED");
    }

    public Mono<Map<String, List<List<String>>>> references(String project, String kind) {
        return Mono.zip(
                        houses(project, 0)
                                .flatMapMany(Flux::fromIterable)
                                .concatMap(h -> archive.context(project, h.spaceId(), false))
                                .collectList(),
                        cashier.options(project),
                        referenceStandards(project))
                .map(
                        tuple -> {
                            Map<String, List<List<String>>> map = new LinkedHashMap<>();
                            var homes = new ArrayList<List<String>>();
                            homes.add(List.of("房屋完整路径", "客户名称"));
                            tuple.getT1()
                                    .forEach(
                                            c ->
                                                    c.customers()
                                                            .forEach(
                                                                    customer ->
                                                                            homes.add(
                                                                                    List.of(
                                                                                            c.house()
                                                                                                    .fullName(),
                                                                                            customer
                                                                                                    .name()))));
                            map.put("房屋客户参考", homes);
                            var stds = new ArrayList<List<String>>();
                            stds.add(List.of("收费科目", "收费标准"));
                            stds.addAll(tuple.getT3());
                            map.put("科目标准参考", stds);
                            var companies = new ArrayList<List<String>>();
                            companies.add(List.of("收款公司", "支付方式", "入账银行"));
                            tuple.getT2()
                                    .forEach(
                                            c ->
                                                    c.funding()
                                                            .forEach(
                                                                    f ->
                                                                            companies.add(
                                                                                    List.of(
                                                                                            c
                                                                                                    .name(),
                                                                                            f
                                                                                                    .text(),
                                                                                            Objects
                                                                                                    .toString(
                                                                                                            f
                                                                                                                    .bankName(),
                                                                                                            "")))));
                            var payments = new ArrayList<List<String>>();
                            payments.add(List.of("支付方式"));
                            tuple.getT2()
                                    .forEach(
                                            c ->
                                                    c.funding()
                                                            .forEach(
                                                                    f -> {
                                                                        var entry =
                                                                                List.of(
                                                                                        paymentLabel(
                                                                                                f));
                                                                        if (!payments.contains(
                                                                                entry))
                                                                            payments.add(entry);
                                                                    }));
                            map.put("支付方式参考", payments);
                            if (map.values().stream().anyMatch(rows -> rows.size() > 50001))
                                throw error("collection_reference_limit", 400);
                            return map;
                        });
    }

    /** 押金允许科目无标准；参考只列当前项目已下发有效科目，不展示未下发标准。 */
    private Mono<List<List<String>>> referenceStandards(String project) {
        return issued.createQuery()
                .where("projectId", project)
                .and("deleted", false)
                .fetch()
                .map(ProjectSubjectEntity::getSubjectId)
                .take(50001)
                .collectList()
                .flatMap(
                        ids -> {
                            if (ids.size() > 50000)
                                return Mono.error(error("collection_reference_limit", 400));
                            if (ids.isEmpty()) return Mono.just(List.<List<String>>of());
                            return Mono.zip(
                                            subjects.createQuery()
                                                    .where("deleted", false)
                                                    .and("enabled", true)
                                                    .in("id", ids)
                                                    .fetch()
                                                    .collectList(),
                                            standards
                                                    .createQuery()
                                                    .where("projectId", project)
                                                    .and("deleted", false)
                                                    .in("subjectId", ids)
                                                    .fetch()
                                                    .take(50001)
                                                    .collectList())
                                    .map(
                                            tuple -> {
                                                if (tuple.getT2().size() > 50000)
                                                    throw error("collection_reference_limit", 400);
                                                var rows = new ArrayList<List<String>>();
                                                for (var subject : tuple.getT1()) {
                                                    var matches =
                                                            tuple.getT2().stream()
                                                                    .filter(
                                                                            s ->
                                                                                    subject.getId()
                                                                                            .equals(
                                                                                                    s
                                                                                                            .getSubjectId()))
                                                                    .toList();
                                                    if (matches.isEmpty())
                                                        rows.add(List.of(subject.getName(), ""));
                                                    else
                                                        matches.forEach(
                                                                s ->
                                                                        rows.add(
                                                                                List.of(
                                                                                        subject
                                                                                                .getName(),
                                                                                        s
                                                                                                .getStandardName())));
                                                }
                                                if (rows.size() > 50000)
                                                    throw error("collection_reference_limit", 400);
                                                return (List<List<String>>) rows;
                                            });
                        });
    }

    private Mono<List<PropertyHouseInfo>> houses(String project, int page) {
        return archive.houses(project, null, null, page, 200)
                .flatMap(
                        result -> {
                            if (result.total() > 50000)
                                return Mono.error(error("collection_reference_limit", 400));
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

    public record Prepared(CollectionImportInput payload) {}
}
