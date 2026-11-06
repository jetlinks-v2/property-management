package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.audit;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.HouseSettingEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.*;

/** 收费设置按钮审批：项目锁串行化版本切换，审批结果与生成应收整批提交或回滚。 */
@Service
@RequiredArgsConstructor
public class HouseSettingReviewService {
    private static final AssetType SPACE = AssetType.of("space");
    private final FinanceScope scope;
    private final FinancialPeriodGate periodGate;
    private final HouseSettingService service;
    private final ReactiveRepository<HouseSettingEntity, String> settings;
    private final ReceivableGenerationService generation;

    public record Item(String id, Long version) {}

    public record Input(List<Item> items, String reason) {}

    @Transactional
    public Mono<List<HouseSettingService.SaveResult>> decide(
            String project, Input input, boolean approve, LocalDate date) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                Mono.defer(
                                        () -> {
                                            validate(input);
                                            // 拒绝也取得项目锁，但允许处理已关期间的申请，不把驳回变成死单。
                                            return periodGate
                                                    .lock(project)
                                                    .flatMap(
                                                            control ->
                                                                    approve
                                                                            ? periodGate.assertOpen(
                                                                                    control, date)
                                                                            : Mono.empty())
                                                    .thenMany(
                                                            Flux.fromIterable(input.items())
                                                                    .concatMap(
                                                                            item ->
                                                                                    locked(
                                                                                            project,
                                                                                            item)))
                                                    .collectList()
                                                    .flatMapMany(
                                                            rows ->
                                                                    Flux.fromIterable(rows)
                                                                            .concatMap(
                                                                                    row ->
                                                                                            write(
                                                                                                    row,
                                                                                                    input.reason()
                                                                                                            .trim(),
                                                                                                    approve,
                                                                                                    auth,
                                                                                                    date)))
                                                    .collectList();
                                        }))
                .as(MonoTracer.create("property.finance.setting.review"));
    }

    private static void validate(Input input) {
        if (input == null
                || input.items() == null
                || input.items().isEmpty()
                || input.items().size() > 100
                || input.reason() == null
                || input.reason().isBlank()
                || input.reason().length() > 1000) throw error("setting_review_invalid", 400);
        var ids = new HashSet<String>();
        for (var item : input.items()) {
            if (item == null
                    || item.id() == null
                    || item.id().isBlank()
                    || item.id().length() > 64
                    || item.version() == null
                    || !ids.add(item.id())) throw error("setting_review_invalid", 400);
        }
    }

    private Mono<HouseSettingEntity> locked(String project, Item item) {
        return service.setting(project, item.id(), true)
                .flatMap(
                        row ->
                                AssetsHolder.assertPermission(
                                                SPACE, row.getSpaceId(), CrudAssetPermission.save)
                                        .then(
                                                Mono.fromCallable(
                                                        () -> {
                                                            if (!Objects.equals(
                                                                    row.getVersion(),
                                                                    item.version()))
                                                                throw error(
                                                                        "version_conflict", 409);
                                                            if (!"PENDING"
                                                                            .equals(
                                                                                    row
                                                                                            .getReviewState())
                                                                    || !"ACTIVE"
                                                                            .equals(
                                                                                    row
                                                                                            .getStatus()))
                                                                throw error(
                                                                        "setting_review_state_invalid",
                                                                        409);
                                                            return row;
                                                        })));
    }

    private Mono<HouseSettingService.SaveResult> write(
            HouseSettingEntity row,
            String reason,
            boolean approve,
            Authentication auth,
            LocalDate date) {
        var ready =
                approve
                        ? periodGate
                                .assertOpen(
                                        row.getProjectId(),
                                        LocalDate.parse(row.getEffectiveStartDate()))
                                .then(service.activateReplacement(row, auth))
                        : Mono.<Void>empty();
        return ready.then(
                        Mono.defer(
                                () ->
                                        audit(settings.createUpdate(), auth, row.getVersion())
                                                .set(
                                                        "reviewState",
                                                        approve ? "APPROVED" : "REJECTED")
                                                .set("reviewedBy", auth.getUser().getId())
                                                .set(
                                                        "reviewedName",
                                                        FinanceScope.operatorName(auth))
                                                .set("reviewedAt", System.currentTimeMillis())
                                                .set("reviewReason", reason)
                                                .where("id", row.getId())
                                                .and("version", row.getVersion())
                                                .and("reviewState", "PENDING")
                                                .and("deleted", false)
                                                .execute()
                                                .flatMap(SubjectService::changed)))
                .then(service.setting(row.getProjectId(), row.getId(), false))
                .flatMap(
                        saved ->
                                approve
                                        ? generation
                                                .generate(saved, date, auth)
                                                .flatMap(
                                                        result ->
                                                                service.view(saved)
                                                                        .map(
                                                                                view ->
                                                                                        new HouseSettingService
                                                                                                .SaveResult(
                                                                                                view,
                                                                                                result
                                                                                                        .generated(),
                                                                                                result
                                                                                                        .retained())))
                                        : service.view(saved)
                                                .map(
                                                        view ->
                                                                new HouseSettingService.SaveResult(
                                                                        view, 0, 0)));
    }
}
