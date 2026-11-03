package org.jetlinks.pro.property.base.service;

import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.base.customer.CustomerRequests;
import org.jetlinks.pro.property.base.entity.PropertyUserEntity;
import org.junit.jupiter.api.Test;

import java.util.*;

class ArchiveImportMatcherTest {
    @Test
    void namesResolveToIdsAndNewFamilyIsGroupedWithinTheHouse() {
        var rows =
                ArchiveImportMatcher.resolve(
                        List.of(person("张三", "001")),
                        "person",
                        List.of(house("101", "园区/101")),
                        List.of(user("user", "张三", "001")),
                        Map.of());
        assertEquals("101", rows.get(0).get("spaceId"));
        assertEquals("user", rows.get(0).get("userId"));
        assertEquals("new", rows.get(0).get("familyMode"));
        assertEquals("张三家庭", rows.get(0).get("familyGroup"));
    }

    @Test
    void sameNameUsesTelephoneButSameNameAndPhoneNeverChoosesFirst() {
        var spaces = List.of(house("101", "园区/101"));
        var users = List.of(user("one", "张三", "001"), user("two", "张三", "002"));
        var row =
                ArchiveImportMatcher.resolve(
                                List.of(person("张三", "002")), "person", spaces, users, Map.of())
                        .get(0);
        assertEquals("two", row.get("userId"));
        var error =
                assertThrows(
                        BusinessException.class,
                        () ->
                                ArchiveImportMatcher.resolve(
                                        List.of(person("张三", "001")),
                                        "person",
                                        spaces,
                                        List.of(user("one", "张三", "001"), user("two", "张三", "001")),
                                        Map.of()));
        assertEquals(2, error.getArgs()[0]);
        assertEquals("error.property_excel_match_ambiguous", error.getMessage());
    }

    @Test
    void inactiveUsersMissingPathsAndDuplicatePathsAreRejectedAtTheirRealLine() {
        var inactive = user("off", "张三", "001");
        inactive.setStatus((byte) 0);
        assertThrows(
                BusinessException.class,
                () ->
                        ArchiveImportMatcher.resolve(
                                List.of(person("张三", "001")),
                                "person",
                                List.of(house("101", "园区/101")),
                                List.of(inactive),
                                Map.of()));
        var error =
                assertThrows(
                        BusinessException.class,
                        () ->
                                ArchiveImportMatcher.resolve(
                                        List.of(
                                                Map.of("spacePath", "园区/101"),
                                                Map.of("spacePath", "园区/102")),
                                        "house",
                                        List.of(house("101", "园区/101")),
                                        List.of(),
                                        Map.of()));
        assertEquals(3, error.getArgs()[0]);
        assertThrows(
                BusinessException.class,
                () ->
                        ArchiveImportMatcher.resolve(
                                List.of(Map.of("spacePath", "园区/101")),
                                "house",
                                List.of(house("101", "园区/101"), house("other", "园区/101")),
                                List.of(),
                                Map.of()));
    }

    @Test
    void existingFamiliesAreMatchedOnlyWithinTheSelectedHouseAndMustBeUnique() {
        var row = new HashMap<>(person("张三", "001"));
        row.put("familyMode", "加入已有家庭");
        var spaces = List.of(house("101", "园区/101"));
        var users = List.of(user("user", "张三", "001"));
        var families = Map.of("101", List.of(new CustomerRequests.Family("family", "张三家庭")));
        var resolved =
                ArchiveImportMatcher.resolve(List.of(row), "person", spaces, users, families)
                        .get(0);
        assertEquals("family", resolved.get("familyId"));
        assertFalse(resolved.containsKey("familyName"));
        assertThrows(
                BusinessException.class,
                () ->
                        ArchiveImportMatcher.resolve(
                                List.of(row),
                                "person",
                                spaces,
                                users,
                                Map.of("102", families.get("101"))));
        assertThrows(
                BusinessException.class,
                () ->
                        ArchiveImportMatcher.resolve(
                                List.of(row),
                                "person",
                                spaces,
                                users,
                                Map.of(
                                        "101",
                                        List.of(
                                                new CustomerRequests.Family("one", "张三家庭"),
                                                new CustomerRequests.Family("two", "张三家庭")))));
    }

    @Test
    void companyMatchesNameWithoutPersonalOrFamilyFields() {
        var resolved =
                ArchiveImportMatcher.resolve(
                                List.of(
                                        Map.of(
                                                "spacePath",
                                                "园区/101",
                                                "companyName",
                                                "测试企业",
                                                "telephone",
                                                "001")),
                                "company",
                                List.of(house("101", "园区/101")),
                                List.of(user("company", "测试企业", "001")),
                                Map.of())
                        .get(0);
        assertEquals("company", resolved.get("userId"));
        assertFalse(resolved.containsKey("familyId"));
    }

    static Map<String, Object> person(String name, String phone) {
        return Map.of(
                "spacePath",
                "园区/101",
                "personName",
                name,
                "telephone",
                phone,
                "familyMode",
                "新建家庭",
                "familyName",
                "张三家庭");
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
