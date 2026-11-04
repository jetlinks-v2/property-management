package org.jetlinks.pro.property.base.service;

import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.base.entity.PropertyUserEntity;
import org.junit.jupiter.api.Test;

import java.util.*;

class ArchiveImportMatcherTest {
    @Test
    void pathsAndCustomerNamesResolveExactlyWithoutFamilyFields() {
        var values =
                ArchiveImportMatcher.resolve(
                        List.of(person("张三", "001")),
                        "person",
                        List.of(house("101", "园区/101")),
                        List.of(user("user", "张三", "001")));
        assertEquals("101", values.get(0).get("spaceId"));
        assertEquals("user", values.get(0).get("userId"));
        assertFalse(values.get(0).containsKey("familyId"));
    }

    @Test
    void sameNamesRequireUniqueTelephoneAndMissingCustomerReportsItsRow() {
        var spaces = List.of(house("101", "园区/101"));
        var users = List.of(user("one", "张三", "001"), user("two", "张三", "002"));
        assertEquals(
                "two",
                ArchiveImportMatcher.resolve(List.of(person("张三", "002")), "person", spaces, users)
                        .get(0)
                        .get("userId"));
        var error =
                assertThrows(
                        BusinessException.class,
                        () ->
                                ArchiveImportMatcher.resolve(
                                        List.of(person("张三", "001"), person("不存在", "003")),
                                        "person",
                                        spaces,
                                        users));
        assertEquals(3, error.getArgs()[0]);
        assertThrows(
                BusinessException.class,
                () ->
                        ArchiveImportMatcher.resolve(
                                List.of(person("张三", "001")),
                                "person",
                                spaces,
                                List.of(user("one", "张三", "001"), user("two", "张三", "001"))));
    }

    @Test
    void unknownAmbiguousPathsAndDisabledUsersAreRejected() {
        var row = person("张三", "001");
        assertThrows(
                BusinessException.class,
                () -> ArchiveImportMatcher.resolve(List.of(row), "person", List.of(), List.of()));
        assertThrows(
                BusinessException.class,
                () ->
                        ArchiveImportMatcher.resolve(
                                List.of(row),
                                "person",
                                List.of(house("101", "园区/101"), house("102", "园区/101")),
                                List.of(user("user", "张三", "001"))));
        var disabled = user("user", "张三", "001");
        disabled.setStatus((byte) 0);
        assertThrows(
                BusinessException.class,
                () ->
                        ArchiveImportMatcher.resolve(
                                List.of(row),
                                "person",
                                List.of(house("101", "园区/101")),
                                List.of(disabled)));
    }

    static Map<String, Object> person(String name, String phone) {
        return Map.of("spacePath", "园区/101", "personName", name, "telephone", phone);
    }

    static PropertyHouseService.HouseView house(String id, String path) {
        var h = new PropertyHouseService.HouseView();
        h.setSpaceId(id);
        h.setFullName(path);
        return h;
    }

    static PropertyUserEntity user(String id, String name, String phone) {
        var u = new PropertyUserEntity();
        u.setId(id);
        u.setName(name);
        u.setTelephone(phone);
        u.setOwner(true);
        u.setStatus((byte) 1);
        u.setDeleted(false);
        return u;
    }
}
