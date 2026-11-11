package org.jetlinks.pro.property.facility.service;

import static org.jetlinks.pro.property.facility.service.FacilityScope.require;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.pro.property.facility.model.MeterUsageEvidence;
import org.jetlinks.pro.property.facility.model.MeterChargeVersion;
import org.jetlinks.pro.property.finance.command.QueryMeterStandardsCommand;
import org.jetlinks.pro.property.finance.meter.MeterBillingModels;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.*;

/** Owns facility configuration/evidence. Finance owns formulas, customer assignment and receivables. */
@Service @RequiredArgsConstructor
public class FacilityMeterCharges {
  private final MeterChargeStore charges;
  private final FacilityScope scope;
  private final FacilityRecords records;
  private final FacilityDirectory directory;
  private final FacilityDevices devices;
  private final FacilityMappings mappings;
  private final FacilityReadings readings;

  public Mono<List<MeterBillingModels.Standard>> standards(String project) {
    var command = new QueryMeterStandardsCommand(); command.setProjectId(project);
    return directory.project(project).then(CommandSupportManagerProviders.getCommandSupport(QueryMeterStandardsCommand.SERVICE_ID)
      .flatMap(s -> s.execute(command)));
  }
  public Mono<List<MeterChargeEntity>> rules(String project) {
    return directory.project(project).then(records.assertProject(project, CrudAssetPermission.read)).then(scope.current())
      .flatMap(c -> charges.createQuery().where("tenantId", c.tenantId()).and("applicationId", c.applicationId())
        .and("projectId", project).and("deleted", false).fetch().collectList());
  }
  @Transactional
  public Mono<MeterChargeEntity> save(MeterChargeEntity input) {
    require(Set.of("water", "electricity").contains(Objects.toString(input.getEnergyType(), "")), "allocation_invalid");
    LocalDate start;
    try {
      start = LocalDate.parse(input.getEffectiveFrom());
      require(input.getEffectiveTo() == null || !LocalDate.parse(input.getEffectiveTo()).isBefore(start), "meter_rule_invalid");
    } catch (java.time.format.DateTimeParseException | NullPointerException invalid) {
      throw new org.hswebframework.web.exception.BusinessException("error.facility.meter_rule_invalid", 400);
    }
    return records.assertProject(input.getProjectId(), CrudAssetPermission.save).then(standards(input.getProjectId()))
      .doOnNext(options -> {
        var standard = options.stream().filter(s -> s.id().equals(input.getStandardId()) && s.subjectId().equals(input.getSubjectId()))
          .findFirst().orElseThrow(() -> new org.hswebframework.web.exception.BusinessException("error.facility.meter_rule_invalid", 400));
        var variables = standard.variables();
        require(variables.stream().anyMatch(v -> v.code().equals(input.getUsageVariable()) && !v.system()), "meter_rule_invalid");
        var expected = new HashSet<String>();
        for (var variable : variables) if (!variable.system() && !variable.code().equals(input.getUsageVariable())) expected.add(variable.code());
        require(input.getFixedValues() != null && expected.equals(input.getFixedValues().keySet()) &&
          input.getFixedValues().values().stream().allMatch(v -> v != null && v.signum() >= 0 && v.precision() <= 24), "meter_rule_invalid");
      }).then(scope.current()).flatMap(c -> {
        String id = FacilityRecords.stableId(c.tenantId(), c.applicationId(), input.getProjectId(), input.getEnergyType(), "meter-charge");
        return charges.findById(id).map(Optional::of).defaultIfEmpty(Optional.empty()).flatMap(old -> {
          require(old.isEmpty() ? input.getId() == null : Objects.equals(old.get().getVersion(), input.getVersion()), "conflict");
          var row = old.orElseGet(() -> FacilityScope.initialize(new MeterChargeEntity(), c));
          long version = row.getVersion(); row.setId(id);
          // New rule versions cannot silently reinterpret an earlier month already previewed under old rules.
          if (old.isPresent() && ruleChanged(row, input)) {
            require(!start.isBefore(LocalDate.now(ZoneId.of("Asia/Shanghai"))) && start.toString().compareTo(row.getEffectiveFrom()) > 0,
              "meter_rule_backdate");
            require(row.getHistory().size() < 120, "too_many");
            String through = start.minusDays(1).toString();
            if (row.getEffectiveTo() != null && row.getEffectiveTo().compareTo(through) < 0) through = row.getEffectiveTo();
            var history = new ArrayList<>(row.getHistory()); history.add(MeterChargeVersion.of(row, through)); row.setHistory(history);
          }
          row.setProjectId(input.getProjectId()); row.setOrganizationId(input.getProjectId());
          row.setEnergyType(input.getEnergyType()); row.setSubjectId(input.getSubjectId()); row.setStandardId(input.getStandardId());
          row.setUsageVariable(input.getUsageVariable()); row.setFixedValues(new TreeMap<>(input.getFixedValues()));
          row.setEffectiveFrom(input.getEffectiveFrom()); row.setEffectiveTo(input.getEffectiveTo()); row.setEnabled(input.getEnabled());
          return old.isEmpty() ? records.insert(charges, row, null) :
            records.update(charges, row, version, FacilityRecords.nullFields(row, "effectiveTo"));
        });
      });
  }

  static boolean ruleChanged(MeterChargeEntity old, MeterChargeEntity input) {
    return !Objects.equals(old.getSubjectId(),input.getSubjectId()) || !Objects.equals(old.getStandardId(),input.getStandardId()) ||
      !Objects.equals(old.getUsageVariable(),input.getUsageVariable()) || !sameValues(old.getFixedValues(),input.getFixedValues()) ||
      !Objects.equals(old.getEffectiveFrom(),input.getEffectiveFrom()) || !Objects.equals(old.getEffectiveTo(),input.getEffectiveTo());
  }
  private static boolean sameValues(Map<String,java.math.BigDecimal> a, Map<String,java.math.BigDecimal> b) {
    return a != null && b != null && a.keySet().equals(b.keySet()) &&
      a.entrySet().stream().allMatch(e -> e.getValue() != null && b.get(e.getKey()) != null && e.getValue().compareTo(b.get(e.getKey())) == 0);
  }

  /** Entire month must be proven in the same project/space/type and mapping epoch; no extrapolation. */
  public Mono<MeterUsageEvidence> identity(String id, String month) {
    return devices.read(id).map(d -> new MeterUsageEvidence(d.getId(),d.getCloudDeviceId(),d.getName(),d.getProjectId(),d.getSpaceId(),
      d.getMeterType(),month,null,null,null,Map.of(),null,null,Objects.requireNonNullElse(d.getAllocationSince(),0L),null,null));
  }

  /** Entire month must be proven in the same project/space/type and mapping epoch; no extrapolation. */
  public Mono<MeterUsageEvidence> evidence(String id, String monthText) {
    YearMonth month;
    try { month = YearMonth.parse(monthText); }
    catch (RuntimeException invalid) { return Mono.error(new org.hswebframework.web.exception.BusinessException("error.facility.invalid_period", 400)); }
    var zone = ZoneId.of("Asia/Shanghai");
    long from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
    long to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
    require(to <= System.currentTimeMillis(), "meter_month_open");
    return devices.read(id).flatMap(device -> {
      FacilityDevices.requireActive(device);
      require(Boolean.TRUE.equals(device.getEnergyEnabled()) && Set.of("water", "electricity").contains(Objects.toString(device.getMeterType(), "")) &&
        Boolean.TRUE.equals(device.getProjectConfirmed()) && device.getSpaceId() != null &&
        device.getAllocationSince() != null && device.getAllocationSince() <= from, "meter_allocation_gap");
      return Mono.zip(rules(device.getProjectId()), mappings.forDevice(id)).flatMap(tuple -> {
        var configured = tuple.getT1().stream().filter(r -> Objects.equals(r.getEnergyType(), device.getMeterType()) && Boolean.TRUE.equals(r.getEnabled()))
          .findFirst().orElseThrow(() -> new org.hswebframework.web.exception.BusinessException("error.facility.meter_rule_missing", 409));
        var versions = new ArrayList<>(configured.getHistory()); versions.add(MeterChargeVersion.of(configured,configured.getEffectiveTo()));
        var rule = versions.stream().filter(v -> v.covers(month.atDay(1),month.atEndOfMonth())).findFirst()
          .orElseThrow(() -> new org.hswebframework.web.exception.BusinessException("error.facility.meter_rule_invalid",400));
        var mapping = tuple.getT2();
        require(Boolean.TRUE.equals(mapping.getEnabled()) && Objects.equals(mapping.getEnergyType(), device.getMeterType()) &&
          "submeter".equals(mapping.getMeterLevel()) && mapping.getUsagePropertyId() != null, "meter_mapping_invalid");
        return Mono.zip(readings.seriesFor(mapping, mapping.getUsagePropertyId(), from, to),
          readings.anomalies(id, mapping.getUsagePropertyId(), from, to)).map(data -> {
          var series = data.getT1();
          var usage = EnergyMath.usage(series, from, to, mapping.getStaleAfterSeconds());
          require(usage.complete() && usage.anomalies() == 0 && data.getT2() == 0, "meter_readings_incomplete");
          String facts = series.stream().map(r -> r.getId() + ":" + r.getVersion() + ":" + r.getValue() + ":" + r.getObservedTime())
            .collect(java.util.stream.Collectors.joining("|"));
          String fingerprint = org.apache.commons.codec.digest.DigestUtils.sha256Hex(device.getVersion() + ":" +
            mapping.getVersion() + ":" + rule.version() + ":" + facts);
          return new MeterUsageEvidence(device.getId(), device.getCloudDeviceId(), device.getName(), device.getProjectId(), device.getSpaceId(),
            device.getMeterType(), monthText, rule.subjectId(), rule.standardId(), rule.usageVariable(), rule.fixedValues(),
            usage.value(), fingerprint, device.getAllocationSince(), rule.effectiveFrom(), rule.effectiveTo());
        });
      });
    });
  }
}
