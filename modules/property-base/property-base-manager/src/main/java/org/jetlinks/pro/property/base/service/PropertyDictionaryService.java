package org.jetlinks.pro.property.base.service;

import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.UnAuthorizedException;
import org.jetlinks.pro.property.base.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.jetlinks.pro.property.base.service.ArchiveScope.error;

/** 全系统统一字典，无项目参数；只开放受控字段更新，不提供通用 CRUD。 */
@Service
@RequiredArgsConstructor
public class PropertyDictionaryService {
    private static final Set<String> BOUND_TYPES =
            Set.of("house-type", "house-state", "customer-nature", "certificate-type");
    private final ReactiveRepository<PropertyDictionaryTypeEntity, String> types;
    private final ReactiveRepository<PropertyDictionaryItemEntity, String> items;
    private final ReactiveRepository<PropertyHouseEntity, String> houses;
    private final ReactiveRepository<PropertyCustomerEntity, String> customers;
    private final ReactiveRepository<PropertyCustomerRelationshipEntity, String> relationships;

    public Mono<List<PropertyDictionaryTypeEntity>> types() {
        return authenticated()
                .thenMany(types.createQuery().where("deleted", false).fetch())
                .sort(
                        Comparator.comparing(
                                        PropertyDictionaryTypeEntity::getSortIndex,
                                        Comparator.nullsLast(Long::compareTo))
                                .thenComparing(PropertyDictionaryTypeEntity::getCode))
                .collectList();
    }

    public Mono<List<PropertyDictionaryItemEntity>> items(String code) {
        return authenticated()
                .then(type(code))
                .thenMany(items.createQuery().where("typeCode", code).and("deleted", false).fetch())
                .sort(
                        Comparator.comparing(
                                        PropertyDictionaryItemEntity::getSortIndex,
                                        Comparator.nullsLast(Long::compareTo))
                                .thenComparing(PropertyDictionaryItemEntity::getValue))
                .collectList();
    }

    @Transactional
    public Mono<PropertyDictionaryTypeEntity> createType(TypeRequest input) {
        return authenticated().flatMap(auth -> insertType(input, auth)).onErrorMap(this::duplicate);
    }

    private Mono<PropertyDictionaryTypeEntity> insertType(TypeRequest input, Authentication auth) {
        code(input.code());
        text(input.name(), 128);
        description(input.description());
        var entity = new PropertyDictionaryTypeEntity();
        entity.setId(identity("type", input.code()));
        entity.setCode(input.code());
        entity.setName(input.name().trim());
        entity.setEnabled(input.enabled() == null || input.enabled());
        entity.setSortIndex(input.sortIndex());
        entity.setDescription(input.description());
        init(entity, auth);
        return types.findById(entity.getId())
                .hasElement()
                .flatMap(
                        found ->
                                found
                                        ? Mono.error(error("dictionary_duplicate", 409))
                                        : types.insert(entity).thenReturn(entity));
    }

    @Transactional
    public Mono<PropertyDictionaryTypeEntity> updateType(String id, TypeRequest input) {
        return authenticated()
                .flatMap(
                        auth ->
                                types.findById(id)
                                        .filter(type -> !type.getDeleted())
                                        .switchIfEmpty(
                                                Mono.error(error("dictionary_not_found", 404)))
                                        .flatMap(old -> updateTypeFields(old, input, auth)));
    }

    private Mono<PropertyDictionaryTypeEntity> updateTypeFields(
            PropertyDictionaryTypeEntity old, TypeRequest input, Authentication auth) {
        if (input.code() != null && !input.code().equals(old.getCode())) {
            return Mono.error(error("immutable_code", 400));
        }
        text(input.name(), 128);
        description(input.description());
        version(input.version());
        var update =
                metadata(types.createUpdate(), input.sortIndex(), input.description())
                        .set("name", input.name().trim())
                        .set("enabled", Boolean.TRUE.equals(input.enabled()));
        return audit(update, auth, input.version())
                .where("id", old.getId())
                .and("deleted", false)
                .and("version", input.version())
                .execute()
                .flatMap(
                        changed ->
                                changed == 1
                                        ? types.findById(old.getId())
                                        : Mono.error(error("version_conflict", 409)));
    }

    @Transactional
    public Mono<Void> deleteType(String id, long version) {
        return authenticated()
                .flatMap(
                        auth ->
                                types.findById(id)
                                        .filter(type -> !Boolean.TRUE.equals(type.getDeleted()))
                                        .switchIfEmpty(
                                                Mono.error(error("dictionary_not_found", 404)))
                                        .flatMap(type -> deleteTypeLocked(type, version, auth)));
    }

    private Mono<Void> deleteTypeLocked(
            PropertyDictionaryTypeEntity type, long version, Authentication auth) {
        // 分类行锁与新增选项共用同一事务边界，防止检查后又产生未删除选项。
        return lockType(type.getCode())
                .then(assertTypeDeletable(type.getCode()))
                .then(markTypeDeleted(type.getId(), version, auth));
    }

    private Mono<Void> markTypeDeleted(String id, long version, Authentication auth) {
        var update =
                types.createUpdate()
                        .set("deleted", true)
                        .set("deletedTime", System.currentTimeMillis());
        return audit(update, auth, version)
                .where("id", id)
                .and("deleted", false)
                .and("version", version)
                .execute()
                .flatMap(
                        changed ->
                                changed == 1
                                        ? Mono.empty()
                                        : Mono.error(error("version_conflict", 409)));
    }

    @Transactional
    public Mono<PropertyDictionaryItemEntity> createItem(String code, ItemRequest input) {
        return authenticated()
                .flatMap(
                        auth ->
                                lockType(code)
                                        .then(type(code))
                                        .flatMap(type -> insertItem(code, input, auth)))
                .onErrorMap(this::duplicate);
    }

    private Mono<PropertyDictionaryItemEntity> insertItem(
            String code, ItemRequest input, Authentication auth) {
        code(input.value());
        text(input.text(), 128);
        description(input.description());
        var entity = new PropertyDictionaryItemEntity();
        entity.setId(identity(code, input.value()));
        entity.setTypeCode(code);
        entity.setValue(input.value());
        entity.setText(input.text().trim());
        entity.setEnabled(input.enabled() == null || input.enabled());
        entity.setSortIndex(input.sortIndex());
        entity.setDescription(input.description());
        init(entity, auth);
        return items.findById(entity.getId())
                .hasElement()
                .flatMap(
                        found ->
                                found
                                        ? Mono.error(error("dictionary_duplicate", 409))
                                        : items.insert(entity).thenReturn(entity));
    }

    @Transactional
    public Mono<PropertyDictionaryItemEntity> updateItem(String id, ItemRequest input) {
        return authenticated()
                .flatMap(
                        auth ->
                                items.findById(id)
                                        .filter(item -> !item.getDeleted())
                                        .switchIfEmpty(
                                                Mono.error(error("dictionary_not_found", 404)))
                                        .flatMap(old -> updateItemLocked(old, input, auth)));
    }

    private Mono<PropertyDictionaryItemEntity> updateItemLocked(
            PropertyDictionaryItemEntity old, ItemRequest input, Authentication auth) {
        if ((input.value() != null && !input.value().equals(old.getValue()))
                || (input.typeCode() != null && !input.typeCode().equals(old.getTypeCode()))) {
            return Mono.error(error("immutable_code", 400));
        }
        text(input.text(), 128);
        description(input.description());
        version(input.version());
        return lockType(old.getTypeCode())
                .then(type(old.getTypeCode()))
                .then(updateItemFields(old.getId(), input, auth));
    }

    private Mono<PropertyDictionaryItemEntity> updateItemFields(
            String id, ItemRequest input, Authentication auth) {
        var update =
                metadata(items.createUpdate(), input.sortIndex(), input.description())
                        .set("text", input.text().trim())
                        .set("enabled", Boolean.TRUE.equals(input.enabled()));
        return audit(update, auth, input.version())
                .where("id", id)
                .and("deleted", false)
                .and("version", input.version())
                .execute()
                .flatMap(
                        changed ->
                                changed == 1
                                        ? items.findById(id)
                                        : Mono.error(error("version_conflict", 409)));
    }

    @Transactional
    public Mono<Void> deleteItem(String id, long version) {
        return authenticated()
                .flatMap(
                        auth ->
                                items.findById(id)
                                        .switchIfEmpty(
                                                Mono.error(error("dictionary_not_found", 404)))
                                        .flatMap(old -> deleteItemLocked(old, version, auth)));
    }

    private Mono<Void> deleteItemLocked(
            PropertyDictionaryItemEntity item, long version, Authentication auth) {
        // 与业务新选值共用分类锁，引用校验和逻辑删除必须在同一事务内完成。
        return lockType(item.getTypeCode())
                .then(assertItemUnreferenced(item))
                .then(markItemDeleted(item.getId(), version, auth));
    }

    private Mono<Void> markItemDeleted(String id, long version, Authentication auth) {
        var update =
                items.createUpdate()
                        .set("deleted", true)
                        .set("deletedTime", System.currentTimeMillis());
        return audit(update, auth, version)
                .where("id", id)
                .and("deleted", false)
                .and("version", version)
                .execute()
                .flatMap(
                        changed ->
                                changed == 1
                                        ? Mono.empty()
                                        : Mono.error(error("version_conflict", 409)));
    }

    /** 已保存的原值可以保留；任何新选值必须通过分类及选项双重启用校验。 */
    public Mono<Void> validateSelection(String code, String value, String oldValue) {
        if (value == null || value.isBlank())
            return Mono.error(error("dictionary_value_required", 400));
        if (value.equals(oldValue)) return Mono.empty();
        return lockType(code)
                .then(type(code))
                .flatMap(
                        type -> {
                            if (!Boolean.TRUE.equals(type.getEnabled()))
                                return Mono.error(error("dictionary_disabled", 409));
                            return items.findById(identity(code, value))
                                    .filter(
                                            e ->
                                                    !e.getDeleted()
                                                            && Boolean.TRUE.equals(e.getEnabled()))
                                    .switchIfEmpty(
                                            Mono.error(error("dictionary_value_unavailable", 409)))
                                    .then();
                        });
    }

    private Mono<PropertyDictionaryTypeEntity> type(String code) {
        return types.findById(identity("type", code))
                .filter(e -> !e.getDeleted())
                .switchIfEmpty(Mono.error(error("dictionary_not_found", 404)));
    }

    private Mono<Void> assertTypeDeletable(String code) {
        // 这四个分类已经绑定业务字段，即使业务表暂时为空也不能改变编码的含义。
        if (BOUND_TYPES.contains(code)) return Mono.error(error("dictionary_type_bound", 409));
        return items.createQuery()
                .where("typeCode", code)
                .and("deleted", false)
                .count()
                .flatMap(
                        count ->
                                count == 0
                                        ? Mono.empty()
                                        : Mono.error(error("dictionary_type_has_items", 409)));
    }

    private Mono<Void> assertItemUnreferenced(PropertyDictionaryItemEntity item) {
        // 历史和逻辑删除资料同样需要解析原名称，因此引用检查不能过滤 deleted 或 endTime。
        Mono<Integer> references =
                switch (item.getTypeCode()) {
                    case "house-type" ->
                            houses.createQuery().where("houseType", item.getValue()).count();
                    case "house-state" ->
                            houses.createQuery().where("houseState", item.getValue()).count();
                    case "certificate-type" ->
                            customers
                                    .createQuery()
                                    .where("certificateType", item.getValue())
                                    .count();
                    case "customer-nature" ->
                            relationships.createQuery().where("nature", item.getValue()).count();
                    default -> Mono.just(0);
                };
        return references.flatMap(
                count ->
                        count == 0
                                ? Mono.empty()
                                : Mono.error(error("dictionary_item_in_use", 409)));
    }

    private static <T> org.hswebframework.ezorm.rdb.mapping.ReactiveUpdate<T> metadata(
            org.hswebframework.ezorm.rdb.mapping.ReactiveUpdate<T> update,
            Long sortIndex,
            String description) {
        if (sortIndex == null) update.setNull("sortIndex");
        else update.set("sortIndex", sortIndex);
        if (description == null) update.setNull("description");
        else update.set("description", description);
        return update;
    }

    private static void description(String value) {
        if (value != null && value.length() > 512) throw error("invalid_field", 400);
    }

    private Mono<Void> lockType(String code) {
        String id = identity("type", code);
        var query = new org.hswebframework.web.api.crud.entity.QueryParamEntity();
        query.setForUpdate(true);
        return types.createQuery()
                .setParam(query)
                .where("id", id)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("dictionary_not_found", 404)))
                .then();
    }

    private Mono<Authentication> authenticated() {
        return Authentication.currentReactive()
                .switchIfEmpty(Mono.error(UnAuthorizedException.NoStackTrace::new));
    }

    static void init(ArchiveEntity entity, Authentication auth) {
        entity.setVersion(0L);
        entity.setDeleted(false);
        entity.setCreateTime(System.currentTimeMillis());
        entity.setModifyTime(entity.getCreateTime());
        entity.setModifierId(auth.getUser().getId());
        entity.setModifierName(auth.getUser().getUsername());
    }

    static <T> org.hswebframework.ezorm.rdb.mapping.ReactiveUpdate<T> audit(
            org.hswebframework.ezorm.rdb.mapping.ReactiveUpdate<T> update,
            Authentication auth,
            long version) {
        return update.set("version", version + 1)
                .set("modifyTime", System.currentTimeMillis())
                .set("modifierId", auth.getUser().getId())
                .set("modifierName", auth.getUser().getUsername());
    }

    private Throwable duplicate(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.springframework.dao.DuplicateKeyException
                    || cause
                            instanceof org.hswebframework.ezorm.rdb.exception.DuplicateKeyException)
                return error("dictionary_duplicate", 409);
        }
        return e;
    }

    static String identity(String namespace, String value) {
        return UUID.nameUUIDFromBytes((namespace + "\0" + value).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    static void code(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))
            throw error("invalid_code", 400);
    }

    static void text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max)
            throw error("invalid_field", 400);
    }

    static void version(Long value) {
        if (value == null || value < 0) throw error("version_required", 400);
    }

    public record TypeRequest(
            String code,
            String name,
            Boolean enabled,
            Long version,
            Long sortIndex,
            String description) {
        public TypeRequest(String code, String name, Boolean enabled, Long version) {
            this(code, name, enabled, version, 0L, null);
        }
    }

    public record ItemRequest(
            String value,
            String text,
            Boolean enabled,
            Long version,
            String typeCode,
            Long sortIndex,
            String description) {
        public ItemRequest(
                String value, String text, Boolean enabled, Long version, String typeCode) {
            this(value, text, enabled, version, typeCode, 0L, null);
        }
    }
}
