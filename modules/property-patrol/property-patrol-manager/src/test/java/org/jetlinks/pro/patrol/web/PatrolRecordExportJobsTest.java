package org.jetlinks.pro.patrol.web;

import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.authorization.simple.SimpleUser;
import org.hswebframework.web.exception.BusinessException;
import org.hswebframework.web.exception.NotFoundException;
import org.jetlinks.pro.assets.AssetPermission;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.integration.PatrolProjectContext;
import org.jetlinks.pro.patrol.integration.ProjectTestContexts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PatrolRecordExportJobsTest {
    private final PatrolRecordExportJobs jobs = new PatrolRecordExportJobs();
    private final AssetsHolder assets = mock(AssetsHolder.class);
    private final Context identity = identity("alice", "app", true);
    @AfterEach void close() { jobs.close(); }

    private Context identity(String userId, String app, boolean imagePermission) {
        var auth = mock(Authentication.class); var user = new SimpleUser(); user.setId(userId);
        when(auth.getUser()).thenReturn(user); when(auth.hasPermission("patrol-attachment", "query")).thenReturn(imagePermission);
        return Context.of(Authentication.class, auth, AssetsHolder.class, assets,
                PatrolProjectContext.class, ProjectTestContexts.project(app));
    }
    private PatrolRecordExportJobs.Status finished(String id) {
        return Flux.interval(Duration.ZERO, Duration.ofMillis(5))
                .concatMap(tick -> jobs.status(id).contextWrite(identity))
                .filter(status -> !status.state().equals("running")).next().block(Duration.ofSeconds(5));
    }
    private void permitsReleased() {
        Flux.interval(Duration.ZERO, Duration.ofMillis(5)).filter(tick -> jobs.getRunningCount() == 0)
                .next().block(Duration.ofSeconds(5));
    }
    private void readable() {
        when(assets.assertPermission0(any(AssetType.class), anyCollection(), any(AssetPermission[].class))).thenReturn(Mono.empty());
    }

    @Test void returnsBeforeGenerationCompletesAndKeepsAuthenticationAssetsAndProjectContext() throws Exception {
        var trigger = Sinks.<byte[]>one(); var observed = Sinks.<Context>one();
        var status = jobs.start(job -> Mono.deferContextual(context -> {
            observed.tryEmitValue(Context.of(context)); job.total = 2; job.processed.set(2); return trigger.asMono();
        })).contextWrite(identity).block(Duration.ofSeconds(5));
        assertEquals("running", status.state());
        var captured = observed.asMono().block(Duration.ofSeconds(5));
        assertSame(assets, captured.get(AssetsHolder.class)); assertSame(identity.get(Authentication.class), captured.get(Authentication.class));
        assertEquals("project", captured.<PatrolProjectContext>get(PatrolProjectContext.class).selectedProject());
        StepVerifier.create(jobs.download(status.id()).contextWrite(identity)).expectError(BusinessException.class).verify();
        trigger.tryEmitValue(new byte[]{1, 2, 3});
        assertEquals("ready", finished(status.id()).state());
        var file = jobs.download(status.id()).contextWrite(identity).block();
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(file.getFile().toPath()));
        assertEquals(2, jobs.status(status.id()).contextWrite(identity).block().processedGroups());
        permitsReleased(); jobs.close(); assertFalse(file.exists());
        StepVerifier.create(jobs.status(status.id()).contextWrite(identity)).expectError(NotFoundException.class).verify();
    }

    @Test void statusAndDownloadAreBoundToUserApplicationAndSelectedProject() {
        var status = jobs.start(job -> Mono.never()).contextWrite(identity).block();
        var otherProject = new PatrolProjectContext(ProjectTestContexts.project("app").tree(), null, "app");
        for (var context : List.of(identity("bob", "app", true), identity("alice", "another-app", true),
                identity.put(PatrolProjectContext.class, otherProject))) {
            StepVerifier.create(jobs.status(status.id()).contextWrite(context)).expectError(NotFoundException.class).verify();
            StepVerifier.create(jobs.download(status.id()).contextWrite(context)).expectError(NotFoundException.class).verify();
        }
    }

    @Test void downloadRechecksCurrentImageFunctionAndPlanAndImageAssetPermissions() {
        readable();
        var status = jobs.start(job -> { job.plans = List.of("plan"); job.images.add("photo"); return Mono.just(new byte[]{1}); })
                .contextWrite(identity).block();
        assertEquals("ready", finished(status.id()).state());
        StepVerifier.create(jobs.download(status.id()).contextWrite(identity("alice", "app", false)))
                .expectError(AccessDenyException.class).verify();
        StepVerifier.create(jobs.download(status.id()).contextWrite(identity)).expectNextCount(1).verifyComplete();
        verify(assets).assertPermission0(eq(PatrolAssetType.patrolPlan), eq(java.util.Set.of("plan")), any(AssetPermission[].class));
        verify(assets).assertPermission0(eq(PatrolAssetType.patrolAttachment), eq(java.util.Set.of("photo")), any(AssetPermission[].class));
        when(assets.assertPermission0(eq(PatrolAssetType.patrolAttachment), anyCollection(), any(AssetPermission[].class)))
                .thenReturn(Mono.error(new AccessDenyException.NoStackTrace()));
        StepVerifier.create(jobs.download(status.id()).contextWrite(identity)).expectError(AccessDenyException.class).verify();
        when(assets.assertPermission0(eq(PatrolAssetType.patrolPlan), anyCollection(), any(AssetPermission[].class)))
                .thenReturn(Mono.error(new AccessDenyException.NoStackTrace()));
        StepVerifier.create(jobs.download(status.id()).contextWrite(identity)).expectError(AccessDenyException.class).verify();
    }

    @Test void failureReportsBusinessReasonAndNeverProvidesAPartialWorkbook() {
        var status = jobs.start(job -> Mono.error(new BusinessException("error.patrol_record_export_image_limit")))
                .contextWrite(identity).block();
        var failed = finished(status.id()); assertEquals("failed", failed.state());
        assertEquals("error.patrol_record_export_image_limit", failed.errorCode());
        StepVerifier.create(jobs.download(status.id()).contextWrite(identity)).expectError(BusinessException.class).verify();
        permitsReleased();
    }

    @Test void concurrentGenerationIsBoundedAndClosingCancelsRunningJobs() {
        var cancelled = new AtomicReference<Boolean>(false);
        jobs.start(job -> Mono.<byte[]>never().doOnCancel(() -> cancelled.set(true))).contextWrite(identity).block();
        jobs.start(job -> Mono.never()).contextWrite(identity).block();
        StepVerifier.create(jobs.start(job -> Mono.just(new byte[0])).contextWrite(identity))
                .expectErrorMatches(error -> error instanceof BusinessException business && "error.patrol_record_export_busy".equals(business.getI18nCode())).verify();
        assertEquals(2, jobs.getRunningCount()); jobs.close(); permitsReleased();
        assertEquals(0, jobs.getRetainedCount());
        // 即使订阅尚未开始，关闭也会处置调度任务，之后不可再分配。
        StepVerifier.create(jobs.start(job -> Mono.just(new byte[0])).contextWrite(identity)).expectError(BusinessException.class).verify();
    }

    @Test void expiredJobDeletesCompletedFileAndReleasesRetentionSlot() {
        var reference = new AtomicReference<PatrolRecordExportJobs.Job>();
        var status = jobs.start(job -> { reference.set(job); return Mono.just(new byte[]{1}); }).contextWrite(identity).block();
        finished(status.id()); var job = reference.get(); var path = job.file;
        job.finishedAt = 1; jobs.cleanup().block();
        assertFalse(Files.exists(path)); assertEquals(0, jobs.getRetainedCount());
        StepVerifier.create(jobs.status(status.id()).contextWrite(identity)).expectError(NotFoundException.class).verify();
    }
}
