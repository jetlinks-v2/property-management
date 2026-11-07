package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.stream.IntStream;
import org.hswebframework.ezorm.rdb.mapping.ReactiveUpdate;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.property.facility.entity.JobEntity;
import org.jetlinks.sdk.server.device.DeviceInfo;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** A source snapshot must complete before any facility reconciliation is written. */
class FacilitySourceFailureTest {

  private final FacilityCloud cloud = spy(new FacilityCloud(mock(FacilityScope.class)));

  @Test
  void shortSourcePageDoesNotEmitSnapshot() {
    page(0, 2, rows(0, 1));
    StepVerifier.create(cloud.snapshot("project"))
      .expectError(BusinessException.class)
      .verify();
  }

  @Test
  void duplicateSourceIdsDoNotEmitSnapshot() {
    var duplicate = rows(0, 1).get(0);
    page(0, 2, List.of(duplicate, duplicate));
    StepVerifier.create(cloud.snapshot("project"))
      .expectError(BusinessException.class)
      .verify();
  }

  @Test
  void changedTotalOnLaterPageDoesNotEmitSnapshot() {
    page(0, 101, rows(0, 100));
    page(1, 102, rows(100, 1));
    StepVerifier.create(cloud.snapshot("project"))
      .expectError(BusinessException.class)
      .verify();
    verify(cloud).page("project", 1, 100);
  }

  @Test
  void laterPageDenialDoesNotEmitFirstPage() {
    page(0, 101, rows(0, 100));
    doReturn(Mono.error(new AccessDenyException()))
      .when(cloud).page("project", 1, 100);
    StepVerifier.create(cloud.snapshot("project"))
      .expectError(AccessDenyException.class)
      .verify();
  }

  @Test
  void completePagesEmitAllSourceIdsExactlyOnce() {
    var first = rows(0, 100);
    var second = rows(100, 1);
    page(0, 101, first);
    page(1, 101, second);
    StepVerifier.create(cloud.snapshot("project"))
      .assertNext(snapshot -> {
        assertEquals(101, snapshot.size());
        assertEquals("source-0", snapshot.get(0).getId());
        assertEquals("source-100", snapshot.get(100).getId());
        assertEquals(101, snapshot.stream().map(DeviceInfo::getId).distinct().count());
      })
      .verifyComplete();
  }

  @Test
  @SuppressWarnings("unchecked")
  void failedSourceSnapshotNeverCallsTransactionalWriter() {
    var directory = mock(FacilityDirectory.class);
    var writer = mock(FacilitySyncWriter.class);
    var jobs = mock(JobStore.class);
    var records = mock(FacilityRecords.class);
    var devices = mock(DeviceStore.class);
    ReactiveUpdate<JobEntity> update = mock(ReactiveUpdate.class, RETURNS_SELF);
    when(jobs.createUpdate()).thenReturn(update);
    when(update.execute()).thenReturn(Mono.just(1));
    when(records.assertProject(anyString(), any())).thenReturn(Mono.empty());
    var project = new PropertyOrganizationInfo();
    project.setId("project");
    project.setName("Project");
    when(directory.projects()).thenReturn(Flux.just(project));
    page(0, 101, rows(0, 100));
    doReturn(Mono.error(new AccessDenyException()))
      .when(cloud).page("project", 1, 100);
    var job = new JobEntity();
    job.setId("job");
    job.setProjectId("project");
    var sync = new FacilitySync(writer, cloud, directory, mock(FacilityScope.class), devices, jobs, records);

    StepVerifier.create(sync.run(job))
      .expectError(AccessDenyException.class)
      .verify();

    verifyNoInteractions(writer, devices);
    verify(update).set("state", "failed");
    verify(update, never()).set(eq("lastSuccessTime"), any());
    verify(update).set("leaseUntil", 0L);
  }

  private void page(int index, int total, List<DeviceInfo> data) {
    doReturn(Mono.just(new PagerResult<>(total, data)))
      .when(cloud).page("project", index, 100);
  }

  private static List<DeviceInfo> rows(int offset, int size) {
    return IntStream.range(offset, offset + size).mapToObj(i -> {
      var row = new DeviceInfo();
      row.setId("source-" + i);
      return row;
    }).toList();
  }
}
