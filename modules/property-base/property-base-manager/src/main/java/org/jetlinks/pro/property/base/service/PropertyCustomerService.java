package org.jetlinks.pro.property.base.service;

import lombok.RequiredArgsConstructor;

import org.apache.commons.lang3.StringUtils;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.customer.CustomerRequests;
import org.jetlinks.pro.property.base.customer.CustomerView;
import org.jetlinks.pro.property.base.customer.RelationshipView;
import org.jetlinks.pro.property.base.entity.*;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 项目锁保护主档、家庭和房屋关系的原子写入；通过空间资产范围访问客户。 */
@Service
@RequiredArgsConstructor
public class PropertyCustomerService {
    private static final int LIMIT = 20_000;
    private static final AssetType USER = AssetType.of("user");
    private static final Set<String> CUSTOMER_FIELDS =
            Set.of(
                    "id",
                    "projectId",
                    "userId",
                    "customerType",
                    "nature",
                    "name",
                    "telephone",
                    "email",
                    "address",
                    "certificateType",
                    "certificateNumber",
                    "creditCode",
                    "legalRepresentative",
                    "registeredAddress",
                    "createTime",
                    "modifyTime",
                    "profileComplete",
                    "pendingBinding",
                    "version");
    private static final Set<String> HISTORY_FIELDS =
            Set.of(
                    "id",
                    "projectId",
                    "customerId",
                    "spaceId",
                    "spaceName",
                    "fullName",
                    "nature",
                    "familyId",
                    "familyName",
                    "startTime",
                    "endTime",
                    "endReason",
                    "customer.name",
                    "customer.telephone",
                    "customer.customerType",
                    "customer.userId");
    private final ReactiveRepository<PropertyCustomerEntity, String> customers;
    private final ReactiveRepository<PropertyCustomerRelationshipEntity, String> relationships;
    private final ReactiveRepository<PropertyCustomerFamilyEntity, String> families;
    private final ReactiveRepository<PropertyUserEntity, String> users;
    private final ArchiveScope scope;
    private final PropertyDictionaryService dictionaries;
    private final PropertyHouseService houses;

    public Mono<PagerResult<CustomerView>> query(
            String projectId, String spaceId, QueryParamEntity query) {
        return current(projectId, spaceId)
                .map(list -> ArchiveQuery.page(list, query, CUSTOMER_FIELDS));
    }

    public Mono<List<CustomerView>> export(
            String projectId, String spaceId, QueryParamEntity query) {
        return current(projectId, spaceId)
                .map(list -> ArchiveQuery.filter(list, query, CUSTOMER_FIELDS));
    }

    public Mono<PagerResult<RelationshipView>> history(
            String projectId, String spaceId, QueryParamEntity query) {
        return historical(projectId, spaceId)
                .map(list -> ArchiveQuery.page(list, query, HISTORY_FIELDS));
    }

    public Mono<List<RelationshipView>> exportHistory(
            String projectId, String spaceId, QueryParamEntity query) {
        return historical(projectId, spaceId)
                .map(list -> ArchiveQuery.filter(list, query, HISTORY_FIELDS));
    }

    private Mono<List<CustomerView>> current(String projectId, String spaceId) {
        var visible = scope.spaces(projectId, spaceId, CrudAssetPermission.read)
                .flatMap(
                        spaces ->
                                scopedRelations(projectId, spaces)
                                        .flatMap(rows -> assembleCurrent(rows, spaces)));
        if (StringUtils.isNotBlank(spaceId)) return visible;
        // 绑定客户继续按空间权限过滤，仅真正没有任何关系的主档使用项目权限。
        return visible.flatMap(
                list -> unbound(projectId).map(drafts -> {
                    var all = new ArrayList<>(list);
                    all.addAll(drafts);
                    all.sort(Comparator.comparing(CustomerView::getId));
                    return all;
                }));
    }

    private Mono<List<CustomerView>> unbound(String projectId) {
        return scope.project(projectId, CrudAssetPermission.read)
                .then(bounded(relationships.createQuery().where("projectId", projectId).fetch()))
                .flatMap(links -> {
                    var related = links.stream()
                            .map(PropertyCustomerRelationshipEntity::getCustomerId)
                            .collect(Collectors.toSet());
                    return bounded(customers.createQuery()
                            .where("projectId", projectId).and("deleted", false).fetch())
                            .map(rows -> rows.stream().filter(row -> !related.contains(row.getId()))
                                    .map(PropertyCustomerService::customerView).toList());
                });
    }

    private Mono<List<CustomerView>> assembleCurrent(
            List<PropertyCustomerRelationshipEntity> rows, List<PropertySpaceEntity> spaces) {
        if (rows.isEmpty()) return Mono.just(List.of());
        return Mono.zip(customerMap(rows), familyMap(rows))
                .map(
                        tuple -> {
                            var bySpace = indexSpaces(spaces);
                            // 已结束的有权关系仍让主档可见，便于复用或删除；关系明细只回显当前有效项。
                            var grouped =
                                    rows.stream()
                                            .collect(
                                                    Collectors.groupingBy(
                                                            PropertyCustomerRelationshipEntity
                                                                    ::getCustomerId,
                                                            LinkedHashMap::new,
                                                            Collectors.toList()));
                            var result = new ArrayList<CustomerView>();
                            grouped.forEach(
                                    (id, links) -> {
                                        var customer = tuple.getT1().get(id);
                                        if (customer == null
                                                || Boolean.TRUE.equals(customer.getDeleted()))
                                            return;
                                        var view = customerView(customer);
                                        view.setRelationships(
                                                links.stream()
                                                        .filter(PropertyCustomerService::active)
                                                        .map(
                                                                link ->
                                                                        relationshipView(
                                                                                link,
                                                                                bySpace,
                                                                                tuple.getT2(),
                                                                                null))
                                                        .toList());
                                        result.add(view);
                                    });
                            result.sort(Comparator.comparing(CustomerView::getId));
                            return result;
                        });
    }

    private Mono<List<RelationshipView>> historical(String projectId, String spaceId) {
        return scope.spaces(projectId, spaceId, CrudAssetPermission.read)
                .flatMap(
                        spaces ->
                                scopedRelations(projectId, spaces)
                                        .flatMap(
                                                rows -> {
                                                    var ended =
                                                            rows.stream()
                                                                    .filter(
                                                                            row ->
                                                                                    !Boolean.TRUE
                                                                                                    .equals(
                                                                                                            row
                                                                                                                    .getDeleted())
                                                                                            && row
                                                                                                            .getEndTime()
                                                                                                    != null)
                                                                    .toList();
                                                    if (ended.isEmpty())
                                                        return Mono.just(List.of());
                                                    return Mono.zip(
                                                                    customerMap(ended),
                                                                    familyMap(ended))
                                                            .map(
                                                                    tuple ->
                                                                            ended.stream()
                                                                                    .map(
                                                                                            row ->
                                                                                                    relationshipView(
                                                                                                            row,
                                                                                                            indexSpaces(
                                                                                                                    spaces),
                                                                                                            tuple
                                                                                                                    .getT2(),
                                                                                                            tuple.getT1()
                                                                                                                    .get(
                                                                                                                            row
                                                                                                                                    .getCustomerId())))
                                                                                    .sorted(
                                                                                            Comparator
                                                                                                    .comparing(
                                                                                                            RelationshipView
                                                                                                                    ::getId))
                                                                                    .toList());
                                                }));
    }

    public Mono<CustomerView> detail(String projectId, String id) {
        return authorizedCustomer(projectId, id, CrudAssetPermission.read, false)
                .flatMap(
                        customer ->
                                scope.spaces(projectId, null, CrudAssetPermission.read)
                                        .flatMap(
                                                spaces ->
                                                        scopedRelations(projectId, spaces)
                                                                .flatMap(
                                                                        rows -> {
                                                                            var links =
                                                                                    rows.stream()
                                                                                            .filter(
                                                                                                    row ->
                                                                                                            id
                                                                                                                            .equals(
                                                                                                                                    row
                                                                                                                                            .getCustomerId())
                                                                                                                    && active(
                                                                                                                            row))
                                                                                            .toList();
                                                                            return familyMap(links)
                                                                                    .map(
                                                                                            byFamily -> {
                                                                                                var
                                                                                                        view =
                                                                                                                customerView(
                                                                                                                        customer);
                                                                                                view
                                                                                                        .setRelationships(
                                                                                                                links
                                                                                                                        .stream()
                                                                                                                        .map(
                                                                                                                                row ->
                                                                                                                                        relationshipView(
                                                                                                                                                row,
                                                                                                                                                indexSpaces(
                                                                                                                                                        spaces),
                                                                                                                                                byFamily,
                                                                                                                                                null))
                                                                                                                        .toList());
                                                                                                return view;
                                                                                            });
                                                                        })));
    }

    /** 有关系的客户按空间授权；从未绑定的主档按项目授权，不能借此读取隐藏房屋的客户。 */
    private Mono<PropertyCustomerEntity> authorizedCustomer(
            String projectId, String id, CrudAssetPermission permission, boolean deletedAllowed) {
        return scope.spaces(projectId, null, permission)
                .flatMap(spaces -> scopedRelations(projectId, spaces))
                .flatMap(rows -> {
                    if (rows.stream().anyMatch(row -> id.equals(row.getCustomerId())))
                        return Mono.just(true);
                    return relationships.createQuery().where("projectId", projectId)
                            .and("customerId", id).count()
                            .filter(count -> count == 0)
                            .map(ignored -> true)
                            .switchIfEmpty(Mono.error(error("not_found", 404)));
                })
                .then(customers.findById(id))
                .filter(
                        row ->
                                projectId.equals(row.getProjectId())
                                        && (deletedAllowed
                                                || !Boolean.TRUE.equals(row.getDeleted())))
                .switchIfEmpty(Mono.error(error("not_found", 404)));
    }

    private Mono<List<PropertyCustomerRelationshipEntity>> scopedRelations(
            String projectId, List<PropertySpaceEntity> spaces) {
        if (spaces.isEmpty()) return Mono.just(List.of());
        // 逻辑删除的关系仅保留主档访问证据，避免最后一个误建关联删除后形成无法管理的孤儿主档。
        return bounded(
                relationships
                        .createQuery()
                        .where("projectId", projectId)
                        .in("spaceId", spaces.stream().map(PropertySpaceEntity::getId).toList())
                        .fetch());
    }

    @Transactional
    public Mono<CustomerView> create(String projectId, CustomerRequests.Create request) {
        return writeLock(projectId)
                .then(Mono.defer(() -> createOne(projectId, request, false, new HashMap<>())))
                .flatMap(row -> detail(projectId, row.getId()));
    }

    /** 与完整建档共用项目行锁和账号身份校验，不创建虚构电话、家庭或房屋关系。 */
    @Transactional
    public Mono<CustomerView> createDraft(String projectId, CustomerRequests.Profile request) {
        if (request == null) return Mono.error(error("invalid", 400));
        validateProfile(request, request.getCustomerType(), false);
        String id = UUID.nameUUIDFromBytes(
                (projectId + "\0" + request.getUserId()).getBytes(StandardCharsets.UTF_8)).toString();
        return scope.project(projectId, CrudAssetPermission.save)
                .then(scope.lock(projectId))
                .then(selectableUser(request.getUserId()))
                .then(customers.findById(id).hasElement())
                .flatMap(exists -> {
                    if (exists) return Mono.error(error("exists", 409));
                    var row = new PropertyCustomerEntity();
                    BeanUtils.copyProperties(request, row);
                    row.setId(id);
                    row.setProjectId(projectId);
                    if (StringUtils.isBlank(row.getTelephone())) row.setTelephone("");
                    return validateProfileDictionary(row, null)
                            .then(scope.authenticated())
                            .flatMap(auth -> {
                                PropertyDictionaryService.init(row, auth);
                                return customers.insert(row).thenReturn(row);
                            });
                })
                .map(PropertyCustomerService::customerView);
    }

    @Transactional
    public Mono<CustomerRequests.ImportResult> importCustomers(
            String projectId, List<CustomerRequests.Create> requests) {
        if (requests == null
                || requests.isEmpty()
                || requests.size() > 500
                || requests.stream().anyMatch(Objects::isNull))
            return Mono.error(error("import_invalid", 400));
        return Mono.defer(
                () -> {
                    var groups = new HashMap<String, PropertyCustomerFamilyEntity>();
                    var pairs = new HashSet<String>();
                    var profiles = new HashMap<String, List<String>>();
                    var familyNames = new HashMap<String, String>();
                    return writeLock(projectId)
                            .thenMany(
                                    Flux.range(0, requests.size())
                                            .concatMap(
                                                    index ->
                                                            ImportValidation.at(
                                                                    index + 2,
                                                                    () -> {
                                                                        var request =
                                                                                requests.get(index);
                                                                        validateProfile(
                                                                                request,
                                                                                request
                                                                                        .getCustomerType());
                                                                        validateLink(
                                                                                request
                                                                                        .getCustomerType(),
                                                                                request.link());
                                                                        if (!pairs.add(
                                                                                request.getUserId()
                                                                                        + "\0"
                                                                                        + request
                                                                                                .getSpaceId()))
                                                                            return Mono.error(
                                                                                    error(
                                                                                            "relationship_exists",
                                                                                            409));
                                                                        // 同账号多房屋可复用主档，但不能默默丢弃文件内互相矛盾的个人资料。
                                                                        var profile =
                                                                                Arrays.asList(
                                                                                        request
                                                                                                .getCustomerType(),
                                                                                        request
                                                                                                .getName(),
                                                                                        request
                                                                                                .getTelephone(),
                                                                                        request
                                                                                                .getEmail(),
                                                                                        request
                                                                                                .getAddress(),
                                                                                        request
                                                                                                .getCertificateType(),
                                                                                        request
                                                                                                .getCertificateNumber(),
                                                                                        request
                                                                                                .getCreditCode(),
                                                                                        request
                                                                                                .getLegalRepresentative(),
                                                                                        request
                                                                                                .getRegisteredAddress());
                                                                        var previous =
                                                                                profiles
                                                                                        .putIfAbsent(
                                                                                                request
                                                                                                        .getUserId(),
                                                                                                profile);
                                                                        if (previous != null
                                                                                && !previous.equals(
                                                                                        profile))
                                                                            return Mono.error(
                                                                                    error(
                                                                                            "import_profile_conflict",
                                                                                            400));
                                                                        if (StringUtils.isNotBlank(
                                                                                request
                                                                                        .getFamilyGroup())) {
                                                                            String key =
                                                                                    request
                                                                                                    .getSpaceId()
                                                                                            + "\0"
                                                                                            + request
                                                                                                    .getFamilyGroup();
                                                                            var name =
                                                                                    familyNames
                                                                                            .putIfAbsent(
                                                                                                    key,
                                                                                                    request
                                                                                                            .getFamilyName());
                                                                            if (name != null
                                                                                    && !name.equals(
                                                                                            request
                                                                                                    .getFamilyName()))
                                                                                return Mono.error(
                                                                                        error(
                                                                                                "family_invalid",
                                                                                                400));
                                                                        }
                                                                        return preflightImport(
                                                                                projectId, request);
                                                                    })))
                            .then()
                            // 全量预检通过后才开始插入。事务继续兜底并发变化，禁止逐行容错后部分成功。
                            .thenMany(
                                    Flux.range(0, requests.size())
                                            .concatMap(
                                                    index ->
                                                            ImportValidation.at(
                                                                    index + 2,
                                                                    () ->
                                                                            createOne(
                                                                                    projectId,
                                                                                    requests.get(
                                                                                            index),
                                                                                    true,
                                                                                    groups))))
                            .count()
                            .map(count -> new CustomerRequests.ImportResult(count.intValue()));
                });
    }

    private Mono<Void> preflightImport(String projectId, CustomerRequests.Create request) {
        String id =
                UUID.nameUUIDFromBytes(
                                (projectId + "\0" + request.getUserId())
                                        .getBytes(StandardCharsets.UTF_8))
                        .toString();
        return selectableUser(request.getUserId())
                .then(
                        houses.requireCompleted(
                                projectId, request.getSpaceId(), CrudAssetPermission.save))
                .then(customers.findById(id).map(Optional::of).defaultIfEmpty(Optional.empty()))
                .flatMap(
                        existing -> {
                            if (existing.isPresent()) {
                                var old = existing.get();
                                if (Boolean.TRUE.equals(old.getDeleted())
                                        || !Objects.equals(
                                                old.getCustomerType(), request.getCustomerType()))
                                    return Mono.error(error("exists", 409));
                                return authorizedCustomer(
                                                projectId, id, CrudAssetPermission.save, false)
                                        .then();
                            }
                            var row = new PropertyCustomerEntity();
                            BeanUtils.copyProperties(request, row);
                            return validateProfileDictionary(row, null);
                        })
                .then(
                        relationships
                                .createQuery()
                                .where("projectId", projectId)
                                .and("customerId", id)
                                .and("spaceId", request.getSpaceId())
                                .and("deleted", false)
                                .isNull("endTime")
                                .count())
                .flatMap(
                        count ->
                                count > 0
                                        ? Mono.error(error("relationship_exists", 409))
                                        : Mono.empty())
                .then(
                        Mono.defer(
                                () -> {
                                    if (!"person".equals(request.getCustomerType()))
                                        return Mono.empty();
                                    Mono<Void> family =
                                            "existing".equals(request.getFamilyMode())
                                                    ? families(projectId, request.getSpaceId())
                                                            .filter(
                                                                    list ->
                                                                            list.stream()
                                                                                    .anyMatch(
                                                                                            f ->
                                                                                                    f.id().equals(
                                                                                                                    request
                                                                                                                            .getFamilyId())))
                                                            .switchIfEmpty(
                                                                    Mono.error(
                                                                            error(
                                                                                    "family_invalid",
                                                                                    400)))
                                                            .then()
                                                    : Mono.empty();
                                    return dictionaries
                                            .validateSelection(
                                                    "customer-nature", request.getNature(), null)
                                            .then(family);
                                }));
    }

    private Mono<PropertyCustomerEntity> createOne(
            String projectId,
            CustomerRequests.Create request,
            boolean reuse,
            Map<String, PropertyCustomerFamilyEntity> groups) {
        if (request == null) return Mono.error(error("invalid", 400));
        validateProfile(request, request.getCustomerType());
        String id =
                UUID.nameUUIDFromBytes(
                                (projectId + "\0" + request.getUserId())
                                        .getBytes(StandardCharsets.UTF_8))
                        .toString();
        return selectableUser(request.getUserId())
                .then(
                        customers
                                .findById(id)
                                .flatMap(
                                        existing -> {
                                            // 再次建档只恢复有权管理的同一主档；旧关联保持结束或删除状态。
                                            if (!reuse
                                                    && Boolean.TRUE.equals(existing.getDeleted())) {
                                                return authorizedCustomer(
                                                                projectId,
                                                                existing.getId(),
                                                                CrudAssetPermission.save,
                                                                true)
                                                        .flatMap(
                                                                old -> {
                                                                    request.setVersion(
                                                                            old.getVersion());
                                                                    return updateProfile(
                                                                                    old, request,
                                                                                    true)
                                                                            .then(
                                                                                    customers
                                                                                            .findById(
                                                                                                    old
                                                                                                            .getId()));
                                                                });
                                            }
                                            if (!reuse
                                                    || Boolean.TRUE.equals(existing.getDeleted())
                                                    || !Objects.equals(
                                                            existing.getCustomerType(),
                                                            request.getCustomerType()))
                                                return Mono.error(error("exists", 409));
                                            // 批导复用与单条新增关联遵循同一授权边界，不能利用可选账号认领不可见主档。
                                            return authorizedCustomer(
                                                    projectId,
                                                    existing.getId(),
                                                    CrudAssetPermission.save,
                                                    false);
                                        })
                                .switchIfEmpty(
                                        Mono.defer(() -> insertCustomer(projectId, id, request))))
                .flatMap(
                        customer ->
                                addRelationship(projectId, customer, request.link(), groups)
                                        .thenReturn(customer));
    }

    private Mono<PropertyCustomerEntity> insertCustomer(
            String projectId, String id, CustomerRequests.Profile request) {
        var row = new PropertyCustomerEntity();
        BeanUtils.copyProperties(request, row);
        row.setId(id);
        row.setProjectId(projectId);
        row.setVersion(0L);
        row.setDeleted(false);
        row.setCreateTime(System.currentTimeMillis());
        row.setModifyTime(row.getCreateTime());
        return validateProfileDictionary(row, null)
                .then(scope.authenticated())
                .flatMap(
                        auth -> {
                            PropertyDictionaryService.init(row, auth);
                            return customers.insert(row).thenReturn(row);
                        });
    }

    private Mono<PropertyUserEntity> selectableUser(String userId) {
        if (StringUtils.isBlank(userId)) return Mono.error(error("user_invalid", 400));
        return scope.authenticated()
                .then(AssetsHolder.assertPermission(USER, userId, CrudAssetPermission.read))
                .then(users.findById(userId))
                .filter(
                        user ->
                                !Boolean.TRUE.equals(user.getDeleted())
                                        && Boolean.TRUE.equals(user.getOwner())
                                        && Byte.valueOf((byte) 1).equals(user.getStatus()))
                .switchIfEmpty(Mono.error(error("user_invalid", 400)));
    }

    @Transactional
    public Mono<CustomerView> update(
            String projectId, String id, CustomerRequests.Profile request) {
        if (request == null || request.getVersion() == null || request.getVersion() < 0)
            return Mono.error(error("invalid", 400));
        return writeLock(projectId)
                .then(authorizedCustomer(projectId, id, CrudAssetPermission.save, false))
                .flatMap(old -> updateProfile(old, request, false))
                .then(detail(projectId, id));
    }

    private Mono<Void> updateProfile(
            PropertyCustomerEntity old, CustomerRequests.Profile request, boolean restoring) {
        if ((request.getUserId() != null && !request.getUserId().equals(old.getUserId()))
                || (request.getCustomerType() != null
                        && !request.getCustomerType().equals(old.getCustomerType())))
            return Mono.error(error("identity_immutable", 400));
        validateProfile(request, old.getCustomerType());
        var row = new PropertyCustomerEntity();
        BeanUtils.copyProperties(request, row);
        row.setCustomerType(old.getCustomerType());
        var update =
                customers
                        .createUpdate()
                        .set("name", row.getName())
                        .set("telephone", row.getTelephone())
                        .set("version", request.getVersion() + 1)
                        .set("modifyTime", System.currentTimeMillis());
        if (restoring) update.set("deleted", false).setNull("deletedTime");
        // 完整资料保存必须将空值写成真正 null，避免残留旧证件和企业资料。
        var optional = new LinkedHashMap<String, String>();
        optional.put("email", row.getEmail());
        optional.put("address", row.getAddress());
        optional.put("certificateType", row.getCertificateType());
        optional.put("certificateNumber", row.getCertificateNumber());
        optional.put("creditCode", row.getCreditCode());
        optional.put("legalRepresentative", row.getLegalRepresentative());
        optional.put("registeredAddress", row.getRegisteredAddress());
        optional.forEach(
                (field, value) -> {
                    if (value == null) update.setNull(field);
                    else update.set(field, value);
                });
        return validateProfileDictionary(row, old)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                PropertyDictionaryService.audit(update, auth, request.getVersion())
                                        .where("id", old.getId())
                                        .and("projectId", old.getProjectId())
                                        .and("deleted", restoring)
                                        .and("version", request.getVersion())
                                        .execute())
                .flatMap(this::checkChanged);
    }

    @Transactional
    public Mono<Void> delete(String projectId, String id, long version) {
        if (version < 0) return Mono.error(error("invalid", 400));
        return writeLock(projectId, CrudAssetPermission.delete)
                .then(authorizedCustomer(projectId, id, CrudAssetPermission.delete, false))
                .then(
                        relationships
                                .createQuery()
                                .where("projectId", projectId)
                                .and("customerId", id)
                                .and("deleted", false)
                                .isNull("endTime")
                                .count())
                .flatMap(
                        count ->
                                count > 0
                                        ? Mono.error(error("active_relationship", 409))
                                        : scope.authenticated()
                                                .flatMap(
                                                        auth ->
                                                                PropertyDictionaryService.audit(
                                                                                customers
                                                                                        .createUpdate()
                                                                                        .set(
                                                                                                "deleted",
                                                                                                true)
                                                                                        .set(
                                                                                                "deletedTime",
                                                                                                System
                                                                                                        .currentTimeMillis()),
                                                                                auth,
                                                                                version)
                                                                        .where("id", id)
                                                                        .and("projectId", projectId)
                                                                        .and("version", version)
                                                                        .and("deleted", false)
                                                                        .execute())
                                                .flatMap(this::checkChanged));
    }

    @Transactional
    public Mono<RelationshipView> link(
            String projectId, String customerId, CustomerRequests.Link request) {
        return writeLock(projectId)
                .then(authorizedCustomer(projectId, customerId, CrudAssetPermission.save, false))
                .flatMap(customer -> addRelationship(projectId, customer, request, new HashMap<>()))
                .flatMap(row -> relationshipDetail(projectId, row));
    }

    private Mono<PropertyCustomerRelationshipEntity> addRelationship(
            String projectId,
            PropertyCustomerEntity customer,
            CustomerRequests.Link request,
            Map<String, PropertyCustomerFamilyEntity> groups) {
        validateLink(customer.getCustomerType(), request);
        return houses.requireCompleted(projectId, request.getSpaceId(), CrudAssetPermission.save)
                .then(
                        relationships
                                .createQuery()
                                .where("projectId", projectId)
                                .and("customerId", customer.getId())
                                .and("spaceId", request.getSpaceId())
                                .and("deleted", false)
                                .isNull("endTime")
                                .count())
                .flatMap(
                        count ->
                                count > 0
                                        ? Mono.error(error("relationship_exists", 409))
                                        : resolveFamily(projectId, customer, request, groups))
                .flatMap(familyId -> insertRelationship(projectId, customer, request, familyId))
                .switchIfEmpty(
                        Mono.defer(() -> insertRelationship(projectId, customer, request, null)));
    }

    private Mono<PropertyCustomerRelationshipEntity> insertRelationship(
            String projectId,
            PropertyCustomerEntity customer,
            CustomerRequests.Link request,
            String familyId) {
        var row = new PropertyCustomerRelationshipEntity();
        row.setId(UUID.randomUUID().toString());
        row.setProjectId(projectId);
        row.setCustomerId(customer.getId());
        row.setSpaceId(request.getSpaceId());
        row.setNature(request.getNature());
        row.setFamilyId(familyId);
        row.setStartTime(request.getStartTime());
        Mono<Void> nature =
                "person".equals(customer.getCustomerType())
                        ? dictionaries.validateSelection(
                                "customer-nature", request.getNature(), null)
                        : Mono.empty();
        return nature.then(scope.authenticated())
                .flatMap(
                        auth -> {
                            PropertyDictionaryService.init(row, auth);
                            return relationships.insert(row).thenReturn(row);
                        });
    }

    private Mono<String> resolveFamily(
            String projectId,
            PropertyCustomerEntity customer,
            CustomerRequests.Link request,
            Map<String, PropertyCustomerFamilyEntity> groups) {
        if ("company".equals(customer.getCustomerType())) return Mono.empty();
        if ("existing".equals(request.getFamilyMode())) {
            return families.findById(request.getFamilyId())
                    .filter(
                            row ->
                                    projectId.equals(row.getProjectId())
                                            && request.getSpaceId().equals(row.getSpaceId()))
                    .flatMap(
                            row ->
                                    relationships
                                            .createQuery()
                                            .where("projectId", projectId)
                                            .and("spaceId", request.getSpaceId())
                                            .and("familyId", row.getId())
                                            .and("deleted", false)
                                            .isNull("endTime")
                                            .count()
                                            .filter(count -> count > 0)
                                            .map(count -> row.getId()))
                    .switchIfEmpty(Mono.error(error("family_invalid", 400)));
        }
        String key =
                StringUtils.isBlank(request.getFamilyGroup())
                        ? null
                        : request.getSpaceId() + "\0" + request.getFamilyGroup();
        if (key != null && groups.containsKey(key)) {
            var row = groups.get(key);
            if (!Objects.equals(row.getName(), request.getFamilyName()))
                return Mono.error(error("family_invalid", 400));
            return Mono.just(row.getId());
        }
        var row = new PropertyCustomerFamilyEntity();
        row.setId(UUID.randomUUID().toString());
        row.setProjectId(projectId);
        row.setSpaceId(request.getSpaceId());
        row.setName(request.getFamilyName());
        return scope.authenticated()
                .flatMap(
                        auth -> {
                            PropertyDictionaryService.init(row, auth);
                            return families.insert(row).thenReturn(row);
                        })
                .map(
                        saved -> {
                            if (key != null) groups.put(key, saved);
                            return saved.getId();
                        });
    }

    @Transactional
    public Mono<RelationshipView> end(String projectId, String id, CustomerRequests.End request) {
        if (request == null
                || request.version() == null
                || request.version() < 0
                || request.endTime() == null
                || request.endTime() <= 0
                || StringUtils.isBlank(request.endReason())
                || request.endReason().length() > 512) return Mono.error(error("end_invalid", 400));
        return writeLock(projectId)
                .then(authorizedRelationship(projectId, id, CrudAssetPermission.save))
                .flatMap(
                        row -> {
                            if (row.getEndTime() != null || request.endTime() < row.getStartTime())
                                return Mono.error(error("end_invalid", 400));
                            return scope.authenticated()
                                    .flatMap(
                                            auth ->
                                                    PropertyDictionaryService.audit(
                                                                    relationships
                                                                            .createUpdate()
                                                                            .set(
                                                                                    "endTime",
                                                                                    request
                                                                                            .endTime())
                                                                            .set(
                                                                                    "endReason",
                                                                                    request
                                                                                            .endReason()),
                                                                    auth,
                                                                    request.version())
                                                            .where("id", id)
                                                            .and("projectId", projectId)
                                                            .and("version", request.version())
                                                            .and("deleted", false)
                                                            .isNull("endTime")
                                                            .execute())
                                    .flatMap(this::checkChanged)
                                    .then(relationships.findById(id));
                        })
                .flatMap(row -> relationshipDetail(projectId, row));
    }

    @Transactional
    public Mono<Void> deleteRelationship(String projectId, String id, long version) {
        if (version < 0) return Mono.error(error("invalid", 400));
        return writeLock(projectId, CrudAssetPermission.delete)
                .then(authorizedRelationship(projectId, id, CrudAssetPermission.delete))
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                PropertyDictionaryService.audit(
                                                relationships
                                                        .createUpdate()
                                                        .set("deleted", true)
                                                        .set(
                                                                "deletedTime",
                                                                System.currentTimeMillis()),
                                                auth,
                                                version)
                                        .where("id", id)
                                        .and("projectId", projectId)
                                        .and("version", version)
                                        .and("deleted", false)
                                        .execute())
                .flatMap(this::checkChanged);
    }

    private Mono<PropertyCustomerRelationshipEntity> authorizedRelationship(
            String projectId, String id, CrudAssetPermission permission) {
        return scope.spaces(projectId, null, permission)
                .flatMap(spaces -> scopedRelations(projectId, spaces))
                .flatMap(
                        rows ->
                                Mono.justOrEmpty(
                                        rows.stream()
                                                .filter(
                                                        row ->
                                                                id.equals(row.getId())
                                                                        && !Boolean.TRUE.equals(
                                                                                row.getDeleted()))
                                                .findFirst()))
                .switchIfEmpty(Mono.error(error("relationship_not_found", 404)));
    }

    private Mono<RelationshipView> relationshipDetail(
            String projectId, PropertyCustomerRelationshipEntity row) {
        return scope.spaces(projectId, row.getSpaceId(), CrudAssetPermission.read)
                .flatMap(
                        spaces ->
                                Mono.zip(customerMap(List.of(row)), familyMap(List.of(row)))
                                        .map(
                                                tuple ->
                                                        relationshipView(
                                                                row,
                                                                indexSpaces(spaces),
                                                                tuple.getT2(),
                                                                tuple.getT1()
                                                                        .get(
                                                                                row
                                                                                        .getCustomerId()))));
    }

    public Mono<List<CustomerRequests.Family>> families(String projectId, String spaceId) {
        if (StringUtils.isBlank(spaceId)) return Mono.error(error("family_invalid", 400));
        return scope.leaf(projectId, spaceId, CrudAssetPermission.read)
                .flatMap(space -> scopedRelations(projectId, List.of(space)))
                .flatMap(
                        rows ->
                                familyMap(
                                        rows.stream()
                                                .filter(PropertyCustomerService::active)
                                                .toList()))
                .map(
                        rows ->
                                rows.values().stream()
                                        .sorted(
                                                Comparator.comparing(
                                                        PropertyCustomerFamilyEntity::getId))
                                        .map(
                                                row ->
                                                        new CustomerRequests.Family(
                                                                row.getId(), row.getName()))
                                        .toList());
    }

    /** 模板与导入匹配复用家庭查询的空间资产read边界，批量装配，避免按每套房屋查询。 */
    public Mono<Map<String, List<CustomerRequests.Family>>> familyReferences(String projectId) {
        return scope.spaces(projectId, null, CrudAssetPermission.read)
                .flatMap(spaces -> scopedRelations(projectId, spaces))
                .map(rows -> rows.stream().filter(PropertyCustomerService::active).toList())
                .flatMap(rows -> familyMap(rows).map(index -> groupFamilies(rows, index)));
    }

    private Map<String, List<CustomerRequests.Family>> groupFamilies(
            List<PropertyCustomerRelationshipEntity> rows,
            Map<String, PropertyCustomerFamilyEntity> index) {
        Map<String, List<CustomerRequests.Family>> result = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (var row : rows) {
            var family = index.get(row.getFamilyId());
            if (family == null || !seen.add(row.getSpaceId() + "\0" + family.getId())) continue;
            result.computeIfAbsent(row.getSpaceId(), ignored -> new ArrayList<>())
                    .add(new CustomerRequests.Family(family.getId(), family.getName()));
        }
        return result;
    }

    private Mono<Void> writeLock(String projectId) {
        return writeLock(projectId, CrudAssetPermission.save);
    }

    private Mono<Void> writeLock(String projectId, CrudAssetPermission permission) {
        return scope.spaces(projectId, null, permission).then(scope.lock(projectId));
    }

    private Mono<Map<String, PropertyCustomerEntity>> customerMap(
            List<PropertyCustomerRelationshipEntity> rows) {
        if (rows.isEmpty()) return Mono.just(Map.of());
        return bounded(
                        customers
                                .createQuery()
                                .in(
                                        "id",
                                        rows.stream()
                                                .map(
                                                        PropertyCustomerRelationshipEntity
                                                                ::getCustomerId)
                                                .distinct()
                                                .toList())
                                .fetch())
                .map(
                        list ->
                                list.stream()
                                        .collect(
                                                Collectors.toMap(
                                                        PropertyCustomerEntity::getId,
                                                        Function.identity())));
    }

    private Mono<Map<String, PropertyCustomerFamilyEntity>> familyMap(
            List<PropertyCustomerRelationshipEntity> rows) {
        var ids =
                rows.stream()
                        .map(PropertyCustomerRelationshipEntity::getFamilyId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        if (ids.isEmpty()) return Mono.just(Map.of());
        return bounded(families.createQuery().in("id", ids).fetch())
                .map(
                        list ->
                                list.stream()
                                        .collect(
                                                Collectors.toMap(
                                                        PropertyCustomerFamilyEntity::getId,
                                                        Function.identity())));
    }

    private static Map<String, PropertySpaceEntity> indexSpaces(List<PropertySpaceEntity> spaces) {
        return spaces.stream()
                .collect(Collectors.toMap(PropertySpaceEntity::getId, Function.identity()));
    }

    private static CustomerView customerView(PropertyCustomerEntity customer) {
        var view = new CustomerView();
        BeanUtils.copyProperties(customer, view);
        return view;
    }

    private static RelationshipView relationshipView(
            PropertyCustomerRelationshipEntity row,
            Map<String, PropertySpaceEntity> spaces,
            Map<String, PropertyCustomerFamilyEntity> families,
            PropertyCustomerEntity customer) {
        var view = new RelationshipView();
        BeanUtils.copyProperties(row, view);
        var space = spaces.get(row.getSpaceId());
        if (space != null) {
            view.setSpaceName(space.getName());
            view.setFullName(space.getFullName());
            view.setSpaceTypeName(space.getSpaceTypeName());
            view.setSpaceLocation(space.getSpaceLocation());
        }
        var family = row.getFamilyId() == null ? null : families.get(row.getFamilyId());
        if (family != null) view.setFamilyName(family.getName());
        view.setCustomer(customer);
        return view;
    }

    private Mono<Void> validateProfileDictionary(
            PropertyCustomerEntity row, PropertyCustomerEntity old) {
        if (!"person".equals(row.getCustomerType()) || row.getCertificateType() == null)
            return Mono.empty();
        return dictionaries.validateSelection(
                "certificate-type",
                row.getCertificateType(),
                old == null ? null : old.getCertificateType());
    }

    static void validateProfile(CustomerRequests.Profile request, String type) {
        validateProfile(request, type, true);
    }

    private static void validateProfile(CustomerRequests.Profile request, String type, boolean requirePhone) {
        if (!Set.of("person", "company").contains(type == null ? "" : type)
                || StringUtils.isBlank(request.getName())
                || request.getName().length() > 128
                || (requirePhone && StringUtils.isBlank(request.getTelephone()))
                || tooLong(request.getTelephone(), 64)
                || tooLong(request.getEmail(), 256)
                || tooLong(request.getAddress(), 512)
                || tooLong(request.getCertificateType(), 64)
                || tooLong(request.getCertificateNumber(), 128)
                || tooLong(request.getCreditCode(), 128)
                || tooLong(request.getLegalRepresentative(), 128)
                || tooLong(request.getRegisteredAddress(), 512)) throw error("invalid", 400);
        if ("company".equals(type)
                && (request.getCertificateType() != null || request.getCertificateNumber() != null))
            throw error("company_person_fields", 400);
        if ("person".equals(type)
                && (request.getCreditCode() != null
                        || request.getLegalRepresentative() != null
                        || request.getRegisteredAddress() != null))
            throw error("person_company_fields", 400);
    }

    static void validateLink(String type, CustomerRequests.Link request) {
        if (request == null
                || StringUtils.isBlank(request.getSpaceId())
                || request.getStartTime() == null
                || request.getStartTime() <= 0) throw error("relationship_invalid", 400);
        if ("company".equals(type)) {
            if (request.getNature() != null
                    || request.getFamilyMode() != null
                    || request.getFamilyId() != null
                    || request.getFamilyName() != null
                    || request.getFamilyGroup() != null) throw error("company_person_fields", 400);
            return;
        }
        if (StringUtils.isBlank(request.getNature()) || request.getNature().length() > 64)
            throw error("relationship_invalid", 400);
        if ("new".equals(request.getFamilyMode())) {
            if (StringUtils.isBlank(request.getFamilyName())
                    || request.getFamilyName().length() > 128
                    || request.getFamilyId() != null
                    || tooLong(request.getFamilyGroup(), 128)) throw error("family_invalid", 400);
        } else if ("existing".equals(request.getFamilyMode())) {
            if (StringUtils.isBlank(request.getFamilyId())
                    || request.getFamilyName() != null
                    || request.getFamilyGroup() != null) throw error("family_invalid", 400);
        } else throw error("family_invalid", 400);
    }

    private static boolean tooLong(String value, int max) {
        return value != null && value.length() > max;
    }

    private static boolean active(PropertyCustomerRelationshipEntity row) {
        return !Boolean.TRUE.equals(row.getDeleted()) && row.getEndTime() == null;
    }

    private Mono<Void> checkChanged(int count) {
        return count == 1 ? Mono.empty() : Mono.error(error("version_conflict", 409));
    }

    /** 内存装配有明确硬上限；额外读取一条用于显式拒绝超限，绝不返回截断结果。 */
    private static <T> Mono<List<T>> bounded(Flux<T> source) {
        return source.take(LIMIT + 1L)
                .collectList()
                .map(
                        list -> {
                            if (list.size() > LIMIT) throw error("query_limit", 400);
                            return list;
                        });
    }

    private static BusinessException error(String key, int status) {
        return new BusinessException.NoStackTrace("error.property_customer_" + key, status);
    }
}
