package org.jetlinks.pro.patrol.service;

import lombok.AllArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.ReactiveAuthenticationHolder;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.*;
import org.jetlinks.pro.patrol.integration.PatrolScope;
import org.jetlinks.pro.patrol.integration.SaasPersonDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.*;

/** 先完成文件与人员校验，再在同一事务内停用本月旧排班、写入新排班及资产绑定。 */
@Service
@AllArgsConstructor
public class PatrolRosterImportService {
    private final PatrolRosterService rosters;
    private final PatrolPostService posts;
    private final PatrolConfigurationValidator validator;
    private final SaasPersonDirectory people;
    private final PatrolScope scope;
    private final ReactiveRepository<PatrolRosterMonthEntity, String> months;
    private final TransactionalOperator transaction;

    public Mono<byte[]> template(YearMonth month) {
        var query = QueryParamEntity.newQuery().is("enabled", true).getParam();
        query.setPaging(false);
        return AssetsHolder.injectQueryParam(query, PatrolAssetType.patrolPost, "id")
                .flatMapMany(posts::query).take(PatrolRosterWorkbook.MAX_ROWS + 1L).collectList()
                .flatMap(rows -> {
                    if (rows.size() > PatrolRosterWorkbook.MAX_ROWS) return Mono.error(PatrolRosterWorkbook.invalid());
                    List<String> ids = rows.stream().flatMap(post -> post.getUserIds().stream()).distinct().toList();
                    if (ids.size() > PatrolRosterWorkbook.MAX_ROWS) return Mono.error(PatrolRosterWorkbook.invalid());
                    return loadNames(ids).flatMap(names -> Mono.fromCallable(() -> PatrolRosterWorkbook.template(month, rows, names))
                            .subscribeOn(Schedulers.boundedElastic()));
                });
    }

    public Mono<Integer> importMonth(YearMonth month, byte[] bytes) {
        return Mono.fromCallable(() -> PatrolRosterWorkbook.read(bytes, month))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(rows -> validateRows(rows).then(replaceMonth(month, rows)))
                .as(MonoTracer.create("/patrol/rosters/import"));
    }

    private Mono<Map<String, String>> loadNames(List<String> ids) {
        return Flux.fromIterable(ids).buffer(200).concatMap(batch -> {
            var query = QueryParamEntity.newQuery().in("id", batch).getParam();
            query.setPageSize(200);
            return people.query(query).flatMap(page -> page.getData().size() == batch.size()
                    ? Mono.just(page.getData()) : Mono.error(new AccessDenyException.NoStackTrace()));
        }).flatMapIterable(list -> list).collectMap(SaasPersonDirectory.Person::id, SaasPersonDirectory.Person::name);
    }

    private Mono<Void> validateRows(List<PatrolRosterEntity> rows) {
        // 同岗位/人员的其余日期来自已验证的模板解析，不重复逐日请求公共人员服务。
        Map<String, PatrolRosterEntity> subjects = new LinkedHashMap<>();
        rows.forEach(row -> subjects.putIfAbsent(row.getPostId() + "|" + row.getUserId(), row));
        return Flux.fromIterable(subjects.values()).concatMap(validator::validate).then()
                .then(loadNames(rows.stream().map(PatrolRosterEntity::getUserId).distinct().toList()))
                .doOnNext(names -> rows.forEach(row -> row.setUserName(names.get(row.getUserId())))).then();
    }

    private Mono<Integer> replaceMonth(YearMonth month, List<PatrolRosterEntity> rows) {
        return scope.current().zipWith(ReactiveAuthenticationHolder.get()).flatMap(pair -> {
            String key = pair.getT1().key(), user = pair.getT2().getUser().getId();
            long now = System.currentTimeMillis();
            rows.forEach(row -> {
                row.setId(UUID.randomUUID().toString()); row.setScopeKey(key);
                row.setCreatorId(user); row.setModifierId(user);
                row.setCreatorName(java.util.Objects.requireNonNullElse(pair.getT2().getUser().getName(), "")); row.setModifierName(row.getCreatorName()); row.setCreateTime(now); row.setModifyTime(now);
                row.setDescription(""); row.setExtensions(new HashMap<>());
            });
            return lockMonth(key, month)
                    .thenMany(rosters.createQuery().where(PatrolRosterEntity::getScopeKey, key)
                            .and(PatrolRosterEntity::getBusinessDate, "gte", month.atDay(1))
                            .and(PatrolRosterEntity::getBusinessDate, "lte", month.atEndOfMonth())
                            .and(PatrolRosterEntity::isEnabled, true).fetch())
                    // 整月替换必须拥有全部既有排班的 save；不把看不见的排班静默覆盖。
                    .concatMap(row -> AssetsHolder.assertPermission(PatrolAssetType.patrolRoster, row.getId(), CrudAssetPermission.save))
                    .then(rosters.createUpdate().set(PatrolRosterEntity::isEnabled, false)
                            .set(PatrolRosterEntity::getModifierId, user).set(PatrolRosterEntity::getModifyTime, now)
                            .set(PatrolRosterEntity::getModifierName, java.util.Objects.requireNonNullElse(pair.getT2().getUser().getName(), ""))
                            .where(PatrolRosterEntity::getScopeKey, key)
                            .and(PatrolRosterEntity::getBusinessDate, "gte", month.atDay(1))
                            .and(PatrolRosterEntity::getBusinessDate, "lte", month.atEndOfMonth())
                            .and(PatrolRosterEntity::isEnabled, true).execute())
                    .then(rows.isEmpty() ? Mono.empty() : rosters.insertBatch(Flux.fromIterable(rows).buffer(200)).then())
                    .thenMany(AssetsHolder.bindAssets(Flux.fromIterable(rows), PatrolAssetType.patrolRoster, PatrolRosterEntity::getId))
                    .then(Mono.just(rows.size()));
        }).as(transaction::transactional);
    }

    private Mono<Void> lockMonth(String key, YearMonth month) {
        String id = UUID.nameUUIDFromBytes((key + "|" + month).getBytes(StandardCharsets.UTF_8)).toString();
        return months.findById(id).flatMap(old -> months.createUpdate()
                .set(PatrolRosterMonthEntity::getVersion, old.getVersion() + 1)
                .where(PatrolRosterMonthEntity::getId, id).and(PatrolRosterMonthEntity::getVersion, old.getVersion())
                .execute().flatMap(count -> count == 1 ? Mono.just(true)
                        : Mono.error(new BusinessException("error.patrol.roster.concurrent"))))
                .switchIfEmpty(Mono.defer(() -> {
                    var marker = new PatrolRosterMonthEntity(); marker.setId(id); marker.setVersion(1);
                    return months.insert(marker).thenReturn(true);
                })).then();
    }
}
