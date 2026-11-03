package org.jetlinks.pro.property.base.service;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.UnAuthorizedException;
import org.hswebframework.web.crud.query.QueryHelper;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.entity.PropertyUserEntity;
import org.jetlinks.pro.property.base.entity.PropertyUserOrganizationEntity;
import org.jetlinks.pro.property.base.user.UserIdentity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PropertyUserService {
    static final AssetType USER = AssetType.of("user");
    private final ReactiveRepository<PropertyUserEntity, String> users;
    private final ReactiveRepository<PropertyUserOrganizationEntity, String> organizations;

    private Mono<QueryParamEntity> scoped(QueryParamEntity input) {
        var query = input.clone();
        query.setTotal(null);
        query.setPaging(true);
        query.setPageSize(Math.max(1, Math.min(200, query.getPageSize())));
        query.setPageIndex(Math.max(0, query.getPageIndex()));
        query.toNestQuery(q -> q.and("deleted", false));
        // 沿用 SaaS 原 user 资产；必须在 count/分页之前注入，不能返回后内存过滤。
        return authenticated().then(AssetsHolder.injectQueryParam(query, USER, "id"));
    }

    public Mono<PagerResult<PropertyUserEntity>> query(QueryParamEntity input) {
        return scoped(input)
                .flatMap(query -> QueryHelper.queryPager(query, users::createQuery))
                .flatMap(
                        page ->
                                QueryHelper.transformPageResult(
                                        Mono.just(page), this::withOrganizations));
    }

    /** Excel 用户参考沿用统一资产范围；显式拒绝超限，不能截断可选用户。 */
    public Mono<List<PropertyUserEntity>> customerReferences() {
        return scoped(new QueryParamEntity())
                .flatMap(
                        query -> {
                            query.setPaging(false);
                            return users.createQuery()
                                    .setParam(query)
                                    .and("owner", true)
                                    .and("status", 1)
                                    .fetch()
                                    .take(10_001)
                                    .collectList()
                                    .map(
                                            rows -> {
                                                if (rows.size() > 10_000)
                                                    throw org.jetlinks.pro.property.base.excel
                                                            .ArchiveExcel.failure(
                                                            "reference_limit");
                                                return rows;
                                            });
                        });
    }

    /** 兼容旧版把账号写入姓名的记录；仅补全展示，不改写已保存的审计姓名快照。 */
    private Mono<List<PropertyUserEntity>> withModifierNames(List<PropertyUserEntity> list) {
        var modifierIds =
                list.stream()
                        .map(PropertyUserEntity::getModifierId)
                        .filter(StringUtils::hasText)
                        .distinct()
                        .toList();
        if (modifierIds.isEmpty()) return Mono.just(list);
        // 批量只读取姓名解析所需字段，不返回操作人的完整用户资料。
        return users.createQuery()
                .select("id", "username", "name")
                .in("id", modifierIds)
                .fetch()
                .collectMap(PropertyUserEntity::getId)
                .map(
                        byId -> {
                            list.forEach(
                                    user -> {
                                        var actor = byId.get(user.getModifierId());
                                        if (actor != null
                                                && StringUtils.hasText(actor.getName())
                                                && (!StringUtils.hasText(user.getModifierName())
                                                        || user.getModifierName()
                                                                .equals(actor.getUsername())
                                                        || user.getModifierName()
                                                                .equals(actor.getId()))) {
                                            user.setModifierName(actor.getName());
                                        }
                                    });
                            return list;
                        });
    }

    public Mono<Counts> counts(QueryParamEntity input) {
        return scoped(input)
                .flatMap(
                        query ->
                                Mono.zip(
                                                users.createQuery().setParam(query.clone()).count(),
                                                users.createQuery()
                                                        .setParam(query.clone())
                                                        .and("owner", true)
                                                        .count(),
                                                users.createQuery()
                                                        .setParam(query.clone())
                                                        .and("employee", true)
                                                        .count(),
                                                users.createQuery()
                                                        .setParam(query.clone())
                                                        .and("owner", false)
                                                        .and("employee", false)
                                                        .count())
                                        .map(
                                                values ->
                                                        new Counts(
                                                                values.getT1(),
                                                                values.getT2(),
                                                                values.getT3(),
                                                                values.getT4())));
    }

    private Mono<List<PropertyUserEntity>> withOrganizations(List<PropertyUserEntity> list) {
        if (list.isEmpty()) return Mono.just(list);
        return organizations
                .createQuery()
                .in("userId", list.stream().map(PropertyUserEntity::getId).toList())
                .and("deleted", false)
                .fetch()
                .collectMultimap(PropertyUserOrganizationEntity::getUserId)
                .map(
                        byUser -> {
                            list.forEach(
                                    user ->
                                            user.setOrgList(
                                                    List.copyOf(
                                                            byUser.getOrDefault(
                                                                    user.getId(), List.of()))));
                            return list;
                        })
                .flatMap(this::withModifierNames);
    }

    public Mono<PropertyUserEntity> detail(String id) {
        return authenticated()
                .then(AssetsHolder.assertPermission(USER, id, CrudAssetPermission.read))
                .then(active(id))
                .flatMap(user -> withOrganizations(List.of(user)).map(list -> list.get(0)));
    }

    public Mono<PropertyUserEntity> updateIdentities(
            String id, Set<UserIdentity> identities, long version) {
        if (identities == null
                || identities.stream().anyMatch(java.util.Objects::isNull)
                || version < 0)
            return Mono.error(
                    new BusinessException.NoStackTrace(
                            "error.property_user_identity_invalid", 400));
        return authenticated()
                .flatMap(
                        auth ->
                                AssetsHolder.assertPermission(USER, id, CrudAssetPermission.save)
                                        .then(active(id))
                                        .flatMap(
                                                user -> {
                                                    if (user.getStatus() != 1)
                                                        return Mono.error(
                                                                new BusinessException.NoStackTrace(
                                                                        "error.property_user_disabled",
                                                                        409));
                                                    return users.createUpdate()
                                                            .set(
                                                                    "owner",
                                                                    identities.contains(
                                                                            UserIdentity.owner))
                                                            .set(
                                                                    "employee",
                                                                    identities.contains(
                                                                            UserIdentity.employee))
                                                            .set("version", version + 1)
                                                            .set(
                                                                    "modifierId",
                                                                    auth.getUser().getId())
                                                            .set(
                                                                    "modifierName",
                                                                    StringUtils.hasText(
                                                                                    auth.getUser()
                                                                                            .getName())
                                                                            ? auth.getUser()
                                                                                    .getName()
                                                                            : auth.getUser()
                                                                                    .getUsername())
                                                            .set(
                                                                    "modifyTime",
                                                                    System.currentTimeMillis())
                                                            .where("id", id)
                                                            .and("version", version)
                                                            .and("deleted", false)
                                                            .and("status", 1)
                                                            .execute()
                                                            .flatMap(
                                                                    changed ->
                                                                            changed == 1
                                                                                    ? active(id)
                                                                                            .flatMap(
                                                                                                    saved ->
                                                                                                            withOrganizations(
                                                                                                                            List
                                                                                                                                    .of(
                                                                                                                                            saved))
                                                                                                                    .map(
                                                                                                                            list ->
                                                                                                                                    list
                                                                                                                                            .get(
                                                                                                                                                    0)))
                                                                                    : active(id)
                                                                                            .flatMap(
                                                                                                    current ->
                                                                                                            Mono
                                                                                                                    .error(
                                                                                                                            new BusinessException
                                                                                                                                    .NoStackTrace(
                                                                                                                                    current
                                                                                                                                                            .getStatus()
                                                                                                                                                    == 1
                                                                                                                                            ? "error.property_user_version_conflict"
                                                                                                                                            : "error.property_user_disabled",
                                                                                                                                    409))));
                                                }));
    }

    private Mono<PropertyUserEntity> active(String id) {
        return users.findById(id)
                .filter(user -> !Boolean.TRUE.equals(user.getDeleted()))
                .switchIfEmpty(
                        Mono.error(
                                new BusinessException.NoStackTrace(
                                        "error.property_user_not_found", 404)));
    }

    private Mono<Authentication> authenticated() {
        return Authentication.currentReactive()
                .switchIfEmpty(Mono.error(UnAuthorizedException.NoStackTrace::new));
    }

    public record Counts(int all, int owner, int employee, int unmarked) {}
}
