package org.jetlinks.pro.property.facility.service;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.sdk.server.device.DeviceInfo;
import org.springframework.stereotype.Service;
import reactor.core.publisher.*;

/** 只按已保存的业务归属同步；来源组织和页面筛选均不能决定目标项目。 */
@Service
@RequiredArgsConstructor
public class FacilitySyncWriter {
  private final FacilityAllocations allocations;
  private final FacilitySyncDevice devices;
  private final JobStore jobs;

  public Mono<JobEntity> apply(JobEntity job, List<FacilitySync.Source> sources, long now) {
    Map<String, DeviceInfo> byId = new LinkedHashMap<>();
    Map<String, List<String>> candidates = new HashMap<>();
    for (var source : sources) for (var device : source.devices()) {
      byId.put(device.getId(), device);
      candidates.computeIfAbsent(device.getId(), k -> new ArrayList<>()).add(source.id());
    }
    int[] counts = new int[3];
    var failures = new ArrayList<String>();
    return allocations.configured(job.getProjectId()).concatMap(allocation -> Mono.defer(() -> {
      DeviceInfo source = byId.get(allocation.getCloudDeviceId());
      FacilityScope.require(source != null, "sync_source_unavailable");
      return devices.apply(allocation, source, candidates.get(source.getId()), now)
        .doOnNext(result -> counts[result.created() ? 0 : 1]++).then();
    }).onErrorResume(error -> {
      counts[2]++;
      // Missing visibility is not deletion. Retain the asset and report its failed attempt without disabling billing.
      failures.add(allocation.getName() + ": " + FacilityMeterDrafts.error(error));
      return Mono.empty();
    })).then(Mono.defer(() -> {
      String reason = String.join("; ", failures);
      var update = jobs.createUpdate().set("state", counts[2] == 0 ? "success" : "failed")
        .set("processed", counts[0] + counts[1]).set("inserted", counts[0]).set("updated", counts[1]).set("failed", counts[2])
        .set("nextRunTime", now + (counts[2] == 0 ? 3600000 : 60000)).set("leaseUntil", 0L);
      if (counts[2] == 0) update.set("lastSuccessTime", now).setNull("failureReason");
      else update.set("failureReason", reason.substring(0, Math.min(3900, reason.length())));
      return update.where("id", job.getId()).and("lastStartTime", now).execute()
        .then(jobs.findById(job.getId()));
    }));
  }
}
