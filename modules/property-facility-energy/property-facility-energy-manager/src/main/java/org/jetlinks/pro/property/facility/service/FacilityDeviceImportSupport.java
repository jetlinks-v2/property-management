package org.jetlinks.pro.property.facility.service;

import static org.jetlinks.pro.property.facility.service.FacilityScope.require;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.jetlinks.pro.property.base.space.PropertySpaceInfo;
import org.jetlinks.pro.property.base.user.PropertyUserInfo;
import org.jetlinks.pro.property.facility.entity.DeviceEntity;
import reactor.core.publisher.Mono;

/** 单次导入共享目录快照；空单元格保留旧值，文本引用必须在设备项目中唯一匹配。 */
final class FacilityDeviceImportSupport {
  private static final Set<String> FIELDS = Set.of("name", "systemName", "categoryName", "specification",
    "power", "manufacturer", "purchasePrice", "purchaseDate", "startDate", "warrantyDate", "maintainer",
    "responsibleUserId", "responsibleAccount", "spaceId", "spacePath", "location", "floor", "remark",
    "workEnabled", "energyEnabled");
  private final ObjectMapper mapper;
  private final FacilityDirectory directory;
  private final Map<String, Mono<List<PropertyUserInfo>>> people = new HashMap<>();
  private final Map<String, Mono<List<PropertySpaceInfo>>> spaces = new HashMap<>();

  FacilityDeviceImportSupport(ObjectMapper mapper, FacilityDirectory directory) {
    this.mapper = mapper; this.directory = directory;
  }

  Mono<DeviceEntity> merge(DeviceEntity existing, FacilityDevices.ExtensionImport input) {
    return Mono.defer(() -> {
      require(input.values() != null && FIELDS.containsAll(input.values().keySet()), "invalid_import");
      var values = new HashMap<String, Object>();
      input.values().forEach((key, value) -> {
        if (value != null && (!(value instanceof String text) || !text.isBlank()))
          values.put(key, value instanceof String text ? text.trim() : value);
      });
      // 每行显式指定负责人，即使已有档案带负责人，也不能接受空白负责人单元格。
      require(values.containsKey("responsibleAccount") || values.containsKey("responsibleUserId"), "device_responsible_required");
      require(!values.containsKey("responsibleAccount") || !values.containsKey("responsibleUserId"), "invalid_import");
      require(!values.containsKey("spacePath") || !values.containsKey("spaceId"), "invalid_import");
      validate(values);
      Mono<Void> references = Mono.empty();
      if (values.containsKey("responsibleAccount")) {
        Object account = values.remove("responsibleAccount");
        require(account instanceof String, "invalid_import");
        references = people.computeIfAbsent(existing.getProjectId(), project -> directory.projectEmployeeAccounts(project).collectList().cache())
          .doOnNext(rows -> {
            var matches = rows.stream().filter(person -> account.equals(person.getUsername())).toList();
            require(matches.size() == 1, "import_responsible_account");
            values.put("responsibleUserId", matches.get(0).getId());
          }).then();
      }
      if (values.containsKey("spacePath")) {
        Object path = values.remove("spacePath");
        require(path instanceof String, "invalid_import");
        references = references.then(spaces.computeIfAbsent(existing.getProjectId(), project -> directory.spaces(project).collectList().cache())
          .doOnNext(rows -> {
            var matches = rows.stream().filter(space -> path.equals(space.getFullName())).toList();
            require(matches.size() == 1, "import_space_path");
            values.put("spaceId", matches.get(0).getId());
          }).then());
      }
      return references.then(Mono.fromCallable(() -> {
        var merged = mapper.convertValue(existing, DeviceEntity.class);
        if (values.containsKey("workEnabled")) {
          var enabled = values.remove("workEnabled");
          values.put("inspectionEnabled", enabled); values.put("maintenanceEnabled", enabled);
        }
        mapper.updateValue(merged, values);
        return merged;
      }));
    });
  }

  private static void validate(Map<String, Object> values) {
    values.forEach((key, value) -> {
      if (Set.of("workEnabled", "energyEnabled").contains(key)) require(value instanceof Boolean, "import_boolean");
      else if (Set.of("power", "purchasePrice").contains(key)) {
        try { require(value instanceof Number && new BigDecimal(value.toString()).signum() >= 0, "import_number"); }
        catch (NumberFormatException e) { require(false, "import_number"); }
      } else {
        require(value instanceof String, "invalid_import");
        String text = (String) value;
        require(text.length() <= (key.equals("remark") ? 4000 : 256), "import_text_length");
        if (Set.of("purchaseDate", "startDate", "warrantyDate").contains(key)) {
          try { require(text.matches("\\d{4}-\\d{2}-\\d{2}") && LocalDate.parse(text) != null, "import_date"); }
          catch (java.time.DateTimeException e) { require(false, "import_date"); }
        }
      }
    });
  }
}
