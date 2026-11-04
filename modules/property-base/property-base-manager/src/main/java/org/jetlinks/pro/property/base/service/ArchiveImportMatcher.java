package org.jetlinks.pro.property.base.service;

import static org.jetlinks.pro.property.base.excel.ArchiveExcel.*;

import org.hswebframework.web.exception.BusinessException;
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
            List<PropertyUserEntity> users) {
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
            }
            result.add(row);
        }
        return result;
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
