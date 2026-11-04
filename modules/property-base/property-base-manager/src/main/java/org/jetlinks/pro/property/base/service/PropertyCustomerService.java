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

/** 项目锁保护客户资料和空间记录的原子写入；通过空间资产范围访问客户。 */
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
                    "recordId",
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
                    "startTime",
                    "endTime",
                    "endReason",
                    "customer.name",
                    "customer.telephone",
                    "customer.customerType",
                    "customer.userId");
    private final ReactiveRepository<PropertyCustomerEntity, String> customers;
    private final ReactiveRepository<PropertyCustomerRelationshipEntity, String> relationships;
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
        return scope.spaces(projectId, spaceId, CrudAssetPermission.read)
                .flatMap(
                        spaces ->
                                scopedRelations(projectId, spaces)
                                        .flatMap(rows -> assembleCurrent(rows, spaces)));
    }

    private Mono<List<CustomerView>> assembleCurrent(
            List<PropertyCustomerRelationshipEntity> rows, List<PropertySpaceEntity> spaces) {
        return customerMap(rows)
                .map(
                        byCustomer -> {
                            var result = new ArrayList<CustomerView>();
                            var bySpace = indexSpaces(spaces);
                            for (var row : rows) {
                                var customer = byCustomer.get(row.getCustomerId());
                                if (!active(row)
                                        || customer == null
                                        || Boolean.TRUE.equals(customer.getDeleted())) continue;
                                var view = customerView(customer);
                                view.setRecordId(row.getId());
                                view.setRelationships(
                                        List.of(relationshipView(row, bySpace, null)));
                                result.add(view);
                            }
                            result.sort(Comparator.comparing(CustomerView::getRecordId));
                            return result;
                        });
    }

    /** 已有客户选择按资料去重；有权历史记录仍是复用资料的访问依据。 */
    public Mono<PagerResult<CustomerView>> profiles(String projectId, QueryParamEntity query) {
        return scope.spaces(projectId, null, CrudAssetPermission.read)
                .flatMap(
                        spaces ->
                                scopedRelations(projectId, spaces)
                                        .flatMap(
                                                rows ->
                                                        customerMap(rows)
                                                                .map(
                                                                        byCustomer -> {
                                                                            var values =
                                                                                    byCustomer
                                                                                            .values()
                                                                                            .stream()
                                                                                            .filter(
                                                                                                    row ->
                                                                                                            !Boolean
                                                                                                                    .TRUE
                                                                                                                    .equals(
                                                                                                                            row
                                                                                                                                    .getDeleted()))
                                                                                            .map(
                                                                                                    PropertyCustomerService
                                                                                                            ::customerView)
                                                                                            .sorted(
                                                                                                    Comparator
                                                                                                            .comparing(
                                                                                                                    CustomerView
                                                                                                                            ::getId))
                                                                                            .toList();
                                                                            return ArchiveQuery
                                                                                    .page(
                                                                                            values,
                                                                                            query,
                                                                                            CUSTOMER_FIELDS);
                                                                        })));
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
                                                                    .filter(row -> !active(row))
                                                                    .toList();
                                                    return customerMap(ended)
                                                            .map(
                                                                    byCustomer ->
                                                                            ended.stream()
                                                                                    .map(
                                                                                            row ->
                                                                                                    relationshipView(
                                                                                                            row,
                                                                                                            indexSpaces(
                                                                                                                    spaces),
                                                                                                            byCustomer
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
                                                                .map(
                                                                        rows -> {
                                                                            var view =
                                                                                    customerView(
                                                                                            customer);
                                                                            view.setRelationships(
                                                                                    rows.stream()
                                                                                            .filter(
                                                                                                    row ->
                                                                                                            id
                                                                                                                            .equals(
                                                                                                                                    row
                                                                                                                                            .getCustomerId())
                                                                                                                    && active(
                                                                                                                            row))
                                                                                            .map(
                                                                                                    row ->
                                                                                                            relationshipView(
                                                                                                                    row,
                                                                                                                    indexSpaces(
                                                                                                                            spaces),
                                                                                                                    null))
                                                                                            .toList());
                                                                            return view;
                                                                        })));
    }

    /** 所有读取先以统一空间资产范围缩小关系，再定位主档；已结束关系也可授权历史主档。 */
    private Mono<PropertyCustomerEntity> authorizedCustomer(
            String projectId, String id, CrudAssetPermission permission, boolean deletedAllowed) {
        return scope.spaces(projectId, null, permission)
                .flatMap(spaces -> scopedRelations(projectId, spaces))
                .filter(rows -> rows.stream().anyMatch(row -> id.equals(row.getCustomerId())))
                .switchIfEmpty(Mono.error(error("not_found", 404)))
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
        // 逻辑删除的空间记录用于历史展示，也允许最后一条记录删除后复用已有资料。
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
                .then(Mono.defer(() -> createOne(projectId, request)))
                .flatMap(row -> detail(projectId, row.getId()));
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
                    var pairs = new HashSet<String>();
                    var profiles = new HashMap<String, List<String>>();
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
                                                                        // 性质属于空间，允许同一客户在不同空间填写不同性质；共用资料须一致。
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
                                                                        return preflightImport(
                                                                                projectId, request);
                                                                    })))
                            .then()
                            // 全部行预检成功之后才写入；任一并发校验失败整批事务回滚。
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
                                                                                            index)))))
                            .count()
                            .map(count -> new CustomerRequests.ImportResult(count.intValue()));
                });
    }

    private Mono<Void> preflightImport(String projectId, CustomerRequests.Create request) {
        String id = customerId(projectId, request.getUserId());
        return selectableUser(request.getUserId())
                .then(
                        houses.requireCompleted(
                                projectId, request.getSpaceId(), CrudAssetPermission.save))
                .then(customers.findById(id).map(Optional::of).defaultIfEmpty(Optional.empty()))
                .flatMap(
                        existing -> {
                            if (existing.isPresent()) {
                                if (!Objects.equals(
                                        existing.get().getCustomerType(),
                                        request.getCustomerType()))
                                    return Mono.error(error("identity_immutable", 400));
                                return authorizedCustomer(
                                                projectId, id, CrudAssetPermission.save, true)
                                        .then();
                            }
                            var row = new PropertyCustomerEntity();
                            BeanUtils.copyProperties(request, row);
                            return validateProfileDictionary(row, null);
                        })
                .then(assertNoCurrent(projectId, id, request.getSpaceId()))
                .then(
                        "person".equals(request.getCustomerType())
                                ? dictionaries.validateSelection(
                                        "customer-nature", request.getNature(), null)
                                : Mono.empty());
    }

    private Mono<PropertyCustomerEntity> createOne(
            String projectId, CustomerRequests.Create request) {
        if (request == null) return Mono.error(error("invalid", 400));
        validateLink(request.getCustomerType(), request.link());
        String id = customerId(projectId, request.getUserId());
        return selectableUser(request.getUserId())
                .flatMap(
                        source ->
                                customers
                                        .findById(id)
                                        .flatMap(
                                                existing -> {
                                                    if (!Objects.equals(
                                                            existing.getCustomerType(),
                                                            request.getCustomerType()))
                                                        return Mono.error(
                                                                error("identity_immutable", 400));
                                                    // 复用已保存资料，包括本地补充的电话；再次新增不能被来源资料覆盖。
                                                    return authorizedCustomer(
                                                                    projectId,
                                                                    id,
                                                                    CrudAssetPermission.save,
                                                                    true)
                                                            .flatMap(
                                                                    old ->
                                                                            Boolean.TRUE.equals(
                                                                                            old
                                                                                                    .getDeleted())
                                                                                    ? restoreCustomer(
                                                                                            old)
                                                                                    : Mono.just(
                                                                                            old));
                                                })
                                        .switchIfEmpty(
                                                Mono.defer(
                                                        () -> {
                                                            if (StringUtils.isNotBlank(
                                                                    source.getName()))
                                                                request.setName(source.getName());
                                                            if (StringUtils.isNotBlank(
                                                                    source.getTelephone()))
                                                                request.setTelephone(
                                                                        source.getTelephone());
                                                            if (StringUtils.isNotBlank(
                                                                    source.getEmail()))
                                                                request.setEmail(source.getEmail());
                                                            validateProfile(
                                                                    request,
                                                                    request.getCustomerType());
                                                            return insertCustomer(
                                                                    projectId, id, request);
                                                        })))
                .flatMap(
                        customer ->
                                addRelationship(projectId, customer, request.link())
                                        .thenReturn(customer));
    }

    private static String customerId(String projectId, String userId) {
        return UUID.nameUUIDFromBytes((projectId + "\0" + userId).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private Mono<PropertyCustomerEntity> restoreCustomer(PropertyCustomerEntity old) {
        return scope.authenticated()
                .flatMap(
                        auth ->
                                PropertyDictionaryService.audit(
                                                customers
                                                        .createUpdate()
                                                        .set("deleted", false)
                                                        .setNull("deletedTime"),
                                                auth,
                                                old.getVersion())
                                        .where("id", old.getId())
                                        .and("deleted", true)
                                        .and("version", old.getVersion())
                                        .execute())
                .flatMap(this::checkChanged)
                .then(customers.findById(old.getId()));
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
                .flatMap(
                        old ->
                                updateProfile(old, request, false)
                                        .then(updateNature(projectId, old, request)))
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
    public Mono<RelationshipView> link(
            String projectId, String customerId, CustomerRequests.Link request) {
        return writeLock(projectId)
                .then(authorizedCustomer(projectId, customerId, CrudAssetPermission.save, false))
                .flatMap(customer -> addRelationship(projectId, customer, request))
                .flatMap(row -> relationshipDetail(projectId, row));
    }

    private Mono<PropertyCustomerRelationshipEntity> addRelationship(
            String projectId, PropertyCustomerEntity customer, CustomerRequests.Link request) {
        validateLink(customer.getCustomerType(), request);
        return houses.requireCompleted(projectId, request.getSpaceId(), CrudAssetPermission.save)
                .then(assertNoCurrent(projectId, customer.getId(), request.getSpaceId()))
                .then(insertRelationship(projectId, customer, request));
    }

    private Mono<Void> assertNoCurrent(String projectId, String customerId, String spaceId) {
        return relationships
                .createQuery()
                .where("projectId", projectId)
                .and("customerId", customerId)
                .and("spaceId", spaceId)
                .and("deleted", false)
                .isNull("endTime")
                .count()
                .flatMap(
                        count ->
                                count == 0
                                        ? Mono.empty()
                                        : Mono.error(error("relationship_exists", 409)));
    }

    private Mono<PropertyCustomerRelationshipEntity> insertRelationship(
            String projectId, PropertyCustomerEntity customer, CustomerRequests.Link request) {
        var row = new PropertyCustomerRelationshipEntity();
        row.setId(UUID.randomUUID().toString());
        row.setProjectId(projectId);
        row.setCustomerId(customer.getId());
        row.setSpaceId(request.getSpaceId());
        row.setNature(request.getNature());
        row.setStartTime(System.currentTimeMillis());
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

    @Transactional
    public Mono<Void> deleteRelationship(String projectId, String id, long version) {
        if (version < 0) return Mono.error(error("invalid", 400));
        return writeLock(projectId, CrudAssetPermission.delete)
                .then(authorizedRelationship(projectId, id, CrudAssetPermission.delete))
                .flatMap(
                        row -> {
                            if (!active(row)) return Mono.error(error("version_conflict", 409));
                            long now = System.currentTimeMillis();
                            if (row.getStartTime() > now)
                                return Mono.error(error("end_invalid", 400));
                            return scope.authenticated()
                                    .flatMap(
                                            auth ->
                                                    PropertyDictionaryService.audit(
                                                                    relationships
                                                                            .createUpdate()
                                                                            .set("deleted", true)
                                                                            .set("deletedTime", now)
                                                                            .set("endTime", now),
                                                                    auth,
                                                                    version)
                                                            .where("id", id)
                                                            .and("projectId", projectId)
                                                            .and("version", version)
                                                            .and("deleted", false)
                                                            .isNull("endTime")
                                                            .execute())
                                    .flatMap(this::checkChanged);
                        });
    }

    /** 当前空间性质与客户资料使用同一事务，任一版本冲突不得部分保存。 */
    private Mono<Void> updateNature(
            String projectId, PropertyCustomerEntity customer, CustomerRequests.Profile request) {
        if (request.getRelationshipId() == null) {
            return request.getNature() == null
                    ? Mono.empty()
                    : Mono.error(error("relationship_invalid", 400));
        }
        if (request.getRelationshipVersion() == null || request.getRelationshipVersion() < 0)
            return Mono.error(error("relationship_invalid", 400));
        return authorizedRelationship(
                        projectId, request.getRelationshipId(), CrudAssetPermission.save)
                .flatMap(
                        row -> {
                            if (!customer.getId().equals(row.getCustomerId()) || !active(row))
                                return Mono.error(error("relationship_invalid", 400));
                            var link = new CustomerRequests.Link();
                            link.setSpaceId(row.getSpaceId());
                            link.setNature(request.getNature());
                            validateLink(customer.getCustomerType(), link);
                            return ("person".equals(customer.getCustomerType())
                                            ? dictionaries.validateSelection(
                                                    "customer-nature",
                                                    request.getNature(),
                                                    row.getNature())
                                            : Mono.<Void>empty())
                                    .then(scope.authenticated())
                                    .flatMap(
                                            auth -> {
                                                var update =
                                                        PropertyDictionaryService.audit(
                                                                relationships.createUpdate(),
                                                                auth,
                                                                request.getRelationshipVersion());
                                                if (request.getNature() == null)
                                                    update.setNull("nature");
                                                else update.set("nature", request.getNature());
                                                return update.where("id", row.getId())
                                                        .and("projectId", projectId)
                                                        .and("deleted", false)
                                                        .isNull("endTime")
                                                        .and(
                                                                "version",
                                                                request.getRelationshipVersion())
                                                        .execute();
                                            })
                                    .flatMap(this::checkChanged);
                        });
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
                                customerMap(List.of(row))
                                        .map(
                                                byCustomer ->
                                                        relationshipView(
                                                                row,
                                                                indexSpaces(spaces),
                                                                byCustomer.get(
                                                                        row.getCustomerId()))));
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
            PropertyCustomerEntity customer) {
        var view = new RelationshipView();
        BeanUtils.copyProperties(row, view);
        var space = spaces.get(row.getSpaceId());
        if (space != null) {
            view.setSpaceName(space.getName());
            view.setFullName(space.getFullName());
            view.setSpaceTypeName(space.getSpaceTypeName());
            view.setSpaceLocation(space.getSpaceLocation());
            view.setSpaceSegments(space.getSpaceSegments());
        }
        // 历史误建删除按当时记录的删除时间解析，不伪造历史时间。
        if (view.getEndTime() == null && Boolean.TRUE.equals(row.getDeleted()))
            view.setEndTime(row.getDeletedTime());
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
        if (!Set.of("person", "company").contains(type == null ? "" : type)
                || StringUtils.isBlank(request.getName())
                || request.getName().length() > 128
                || StringUtils.isBlank(request.getTelephone())
                || request.getTelephone().length() > 64
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
        if (request == null || StringUtils.isBlank(request.getSpaceId()))
            throw error("relationship_invalid", 400);
        if ("company".equals(type)) {
            if (request.getNature() != null) throw error("company_person_fields", 400);
        } else if (!"person".equals(type)
                || StringUtils.isBlank(request.getNature())
                || request.getNature().length() > 64) {
            throw error("relationship_invalid", 400);
        }
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
