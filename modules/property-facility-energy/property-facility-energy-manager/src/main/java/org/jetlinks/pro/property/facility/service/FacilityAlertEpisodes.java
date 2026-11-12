package org.jetlinks.pro.property.facility.service;

import lombok.RequiredArgsConstructor;
import org.jetlinks.pro.property.facility.entity.*;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class FacilityAlertEpisodes {
  private final AlertEpisodeStore store;

  public Mono<AlertEpisodeEntity> find(String id) { return store.findById(id); }

  public Mono<Void> snapshot(AlertEntity current) {
    if (current.getFirstTime() == null) return Mono.empty();
    var row = new AlertEpisodeEntity(); BeanUtils.copyProperties(current, row);
    row.setId(FacilityRecords.stableId(current.getId(), String.valueOf(current.getFirstTime())));
    row.setEpisodeId(current.getId());
    return store.save(row).then();
  }
}
