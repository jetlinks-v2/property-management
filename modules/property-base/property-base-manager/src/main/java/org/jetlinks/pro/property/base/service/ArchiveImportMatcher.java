package org.jetlinks.pro.property.base.service;

import static org.jetlinks.pro.property.base.excel.ArchiveExcel.*;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.base.customer.CustomerRequests;
import org.jetlinks.pro.property.base.entity.PropertyUserEntity;

import java.util.*;
import java.util.stream.Collectors;

/** 仅在调用方已按资产权限取得的参考数据中精确匹配；不按重名结果取第一条。 */
final class ArchiveImportMatcher {
    private ArchiveImportMatcher() {}

    static List<Map<String, Object>> resolve(
            List<Map<String, Object>> rows,
            String type,
            List<PropertyHouseService.HouseView> spaces,
            List<PropertyUserEntity> users,
            Map<String, List<CustomerRequests.Family>> families) {
        var spaceIndex =
                spaces.stream()
                        .filter(s -> s.getFullName() != null)
                        .collect(Collectors.groupingBy(s -> s.getFullName().trim()));
        var userIndex =
                users.stream()
                        .filter(
                                u ->
                                        Boolean.TRUE.equals(u.getOwner())
                                                && !Boolean.TRUE.equals(u.getDeleted())
                                                && Byte.valueOf((byte) 1).equals(u.getStatus()))
                        .collect(
                                Collectors.groupingBy(
                                        u ->
                                                normalize(
                                                        u.getName() == null || u.getName().isBlank()
                                                                ? u.getUsername()
                                                                : u.getName())));
        var result = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < rows.size(); i++) {
            int line = i + 2;
            var row = new LinkedHashMap<>(rows.get(i));
            String path = text(row, "spacePath", line, true);
            var space = unique(spaceIndex.getOrDefault(path, List.of()), line, "spacePath", path);
            row.put("spaceId", space.getSpaceId());
            if (!"house".equals(type)) {
                String field = "person".equals(type) ? "personName" : "companyName";
                String name = text(row, field, line, true);
                String telephone = text(row, "telephone", line, true);
                var matches = userIndex.getOrDefault(name, List.of());
                if (matches.size() > 1)
                    matches =
                            matches.stream()
                                    .filter(u -> telephone.equals(normalize(u.getTelephone())))
                                    .toList();
                var user = unique(matches, line, field, name);
                row.put("userId", user.getId());
                row.put("name", name);
                row.put("customerType", type);
                if ("person".equals(type))
                    resolveFamily(row, families.getOrDefault(space.getSpaceId(), List.of()), line);
            }
            result.add(row);
        }
        return result;
    }

    private static void resolveFamily(
            Map<String, Object> row, List<CustomerRequests.Family> families, int line) {
        String mode = text(row, "familyMode", line, true);
        String name = text(row, "familyName", line, true);
        if ("新建家庭".equals(mode)) {
            row.put("familyMode", "new");
            // 组只在本次领域导入中生效，房屋ID由领域分组键再次限定。
            row.put("familyGroup", name);
        } else if ("加入已有家庭".equals(mode)) {
            var family =
                    unique(
                            families.stream().filter(f -> name.equals(f.name())).toList(),
                            line,
                            "familyName",
                            name);
            row.put("familyMode", "existing");
            row.put("familyId", family.id());
            row.remove("familyName");
        } else throw cell(line, "familyMode");
    }

    private static <T> T unique(List<T> matches, int row, String field, String value) {
        if (matches.size() != 1) {
            String key =
                    "error.property_excel_match_" + (matches.isEmpty() ? "missing" : "ambiguous");
            throw new BusinessException.NoStackTrace(key, key, 400, row, header(field), value);
        }
        return matches.get(0);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
