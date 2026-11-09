package org.jetlinks.pro.smartpark.handover.service;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.io.file.FileManager;
import org.jetlinks.pro.smartpark.handover.api.FileRef;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stable platform files must exist and be readable by the current caller before business writes.
 */
@Service
@RequiredArgsConstructor
public class HandoverFiles {
    private final FileManager files;

    /** 前介问题图片限制独立于资料附件，读取平台文件元数据，不信任客户端大小。 */
    public Mono<Void> validateLedger(List<FileRef> references) {
        var refs = references == null ? List.<FileRef>of() : references;
        HandoverRules.require(refs.size() <= 9, "ledger_photos_invalid");
        return validate(refs).thenMany(Flux.fromIterable(refs)
                .concatMap(ref -> files.getFile(ref.getFileId())
                        .doOnNext(file -> HandoverRules.require(file.getLength() <= 2 * 1024 * 1024
                                && file.getName() != null && file.getName().toLowerCase(java.util.Locale.ROOT).matches(".*\\.(jpg|jpeg|png)$"), "ledger_photos_invalid"))))
                .then();
    }

    public Mono<Void> validate(List<FileRef> references) {
        List<FileRef> refs = references == null ? List.of() : references;
        HandoverRules.require(refs.size() <= 100, "file_limit");
        Set<String> unique = new HashSet<>();
        for (FileRef ref : refs) {
            HandoverRules.require(
                    ref != null
                            && HandoverRules.hasText(ref.getFileId())
                            && unique.add(ref.getFileId()),
                    "file_invalid");
            HandoverRules.require(
                    ref.getPhotoType() == null
                            || Set.of("problem", "rectification").contains(ref.getPhotoType()),
                    "file_invalid");
        }
        return Flux.fromIterable(refs)
                .concatMap(
                        ref ->
                                AssetsHolder.assertPermission(
                                                AssetType.of("file"),
                                                ref.getFileId(),
                                                CrudAssetPermission.read)
                                        .then(Mono.defer(() -> files.getFile(ref.getFileId())))
                                        .switchIfEmpty(
                                                Mono.error(
                                                        new BusinessException(
                                                                "error.handover.file_missing",
                                                                400))))
                .then();
    }
}
