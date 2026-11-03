package org.jetlinks.pro.patrol.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.authorization.simple.SimpleUser;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.AssetsHolderHolder;
import org.jetlinks.pro.patrol.entity.PatrolAttachmentEntity;
import org.jetlinks.pro.patrol.integration.PatrolScope;
import org.jetlinks.pro.patrol.integration.PlatformPatrolFiles;
import org.jetlinks.sdk.server.file.FileInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PatrolAttachmentServiceTest {
    private PatrolDatabase db;
    private PatrolAttachmentService attachments;
    private PlatformPatrolFiles files;
    private Context identity;

    @BeforeEach
    void setup() {
        db = new PatrolDatabase();
        files = mock(PlatformPatrolFiles.class);
        var scope = mock(PatrolScope.class);
        when(scope.current()).thenReturn(Mono.just(new PatrolScope.Current("north", "app")));
        attachments = new PatrolAttachmentService(
                db.repository(PatrolAttachmentEntity.class, PatrolAttachmentEntity::new), scope, files,
                TransactionalOperator.create(new R2dbcTransactionManager(db.connections)));
        var user = new SimpleUser();
        user.setId("alice");
        var auth = new SimpleAuthentication();
        auth.setUser(user);
        identity = Context.of(Authentication.class, auth);
    }

    @AfterEach
    void close() { db.close(); }

    private FilePart file(byte[] bytes) {
        var part = mock(FilePart.class);
        when(part.content()).thenAnswer(ignored ->
                Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes)));
        return part;
    }

    @Test
    void rejectsInvalidOrOversizedImagesBeforeCallingPlatform() {
        StepVerifier.create(attachments.upload(file("<svg/>".getBytes()), PatrolAttachmentEntity.Purpose.example)
                        .contextWrite(identity))
                .expectError(BusinessException.class).verify();
        StepVerifier.create(attachments.upload(file(new byte[PlatformPatrolFiles.MAX_IMAGE_BYTES + 1]),
                        PatrolAttachmentEntity.Purpose.example).contextWrite(identity))
                .expectError(BusinessException.class).verify();
        verifyNoInteractions(files);
        assertEquals(0, attachments.createQuery().count().block());
    }

    @Test
    void platformFailureDoesNotRegisterAnAttachment() {
        when(files.upload(anyString(), anyString(), any(byte[].class)))
                .thenReturn(Mono.error(new BusinessException("error.patrol.file.unavailable")));
        StepVerifier.create(attachments.upload(file(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff}),
                        PatrolAttachmentEntity.Purpose.example).contextWrite(identity))
                .expectError(BusinessException.class).verify();
        assertEquals(0, attachments.createQuery().count().block());
    }

    @Test
    void successfulUploadPersistsTrustedMetadataAndBindsTheReturnedBusinessId() {
        var saved = new FileInfo();
        saved.setId("platform-file");
        saved.setAccessUrl("internal-location");
        when(files.upload(anyString(), anyString(), any(byte[].class))).thenReturn(Mono.just(saved));
        var holder = mock(AssetsHolder.class);
        when(holder.bindAssets(any(AssetType.class), anyCollection())).thenReturn(Mono.empty());
        var provider = AssetsHolderHolder.registerProvider(ignored -> Mono.just(holder));
        try {
            var receipt = attachments.upload(file(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff}),
                    PatrolAttachmentEntity.Purpose.evidence).contextWrite(identity).block();
            var stored = attachments.findById(receipt.id()).block();
            assertEquals("north|app", stored.getScopeKey());
            assertEquals("alice", stored.getCreatorId());
            assertEquals(PatrolAttachmentEntity.Purpose.evidence, stored.getPurpose());
            assertEquals("platform-file", stored.getPlatformFileId());
            assertEquals("internal-location", stored.getInternalUrl());
            assertEquals("image/jpeg", receipt.mediaType());
            assertEquals(3, receipt.length());
            verify(holder).bindAssets(eq(org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolAttachment),
                    argThat(ids -> ids.size() == 1 && ids.contains(receipt.id())));
        } finally {
            provider.dispose();
        }
    }

    @Test
    void bindingFailureRollsBackMetadataAndInternalFileFieldsStayPrivate() throws Exception {
        var saved = new FileInfo();
        saved.setId("platform-file");
        saved.setAccessUrl("internal-location");
        when(files.upload(anyString(), anyString(), any(byte[].class))).thenReturn(Mono.just(saved));
        // 这里只替换远端资产绑定边界，数据库事务使用真实 R2DBC 实现。
        var holder = mock(AssetsHolder.class);
        when(holder.bindAssets(any(AssetType.class), anyCollection()))
                .thenReturn(Mono.error(new IllegalStateException("binding unavailable")));
        var provider = AssetsHolderHolder.registerProvider(ignored -> Mono.just(holder));
        try {
            StepVerifier.create(attachments.upload(file(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff}),
                            PatrolAttachmentEntity.Purpose.example).contextWrite(identity))
                    .expectError(IllegalStateException.class).verify();
        } finally {
            provider.dispose();
        }
        assertEquals(0, attachments.createQuery().count().block());
        var entity = new PatrolAttachmentEntity();
        entity.setPlatformFileId("platform-file");
        entity.setInternalUrl("internal-location");
        var json = new ObjectMapper().valueToTree(entity);
        assertFalse(json.has("platformFileId"));
        assertFalse(json.has("internalUrl"));
    }
}
