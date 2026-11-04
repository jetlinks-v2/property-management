package org.jetlinks.pro.smartpark.repair.service;

import org.hswebframework.web.exception.BusinessException;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.smartpark.repair.asset.RepairAssetType;
import org.jetlinks.pro.smartpark.repair.entity.RepairExportEntity;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepairExportStreamingTest {
    @Test void startsWritingBeforeReadingWholeQueryAndRespectsDemand() {
        var content = new RepairExportContent();
        var produced = new AtomicInteger();
        var source = Flux.range(0, 200_000).map(index -> {
            produced.incrementAndGet();
            return order(index);
        });
        StepVerifier.create(content.encode(source), 0)
            .thenRequest(1).assertNext(buffer -> {
                assertTrue(buffer.toString(StandardCharsets.UTF_8).startsWith("\ufeff工单编号"));
                assertEquals(0, produced.get(), "表头应在查询读取前写出");
                DataBufferUtils.release(buffer);
            })
            .thenRequest(1).assertNext(buffer -> {
                assertEquals(256, produced.get(), "一个内容块仅拉取256条，不收集整批工单");
                assertTrue(buffer.toString(StandardCharsets.UTF_8).contains("BS-255"));
                DataBufferUtils.release(buffer);
            }).thenCancel().verify();
        assertEquals(256, content.orderIds().size());
    }

    @Test void exportsTwoHundredThousandRowsWithoutCollectingEntitiesOrFileBody() {
        var content = new RepairExportContent();
        long[] totals = new long[3];
        StepVerifier.create(content.encode(Flux.range(0, 200_000).map(RepairExportStreamingTest::order))
            .doOnNext(buffer -> {
                String text = buffer.toString(StandardCharsets.UTF_8);
                totals[0] += buffer.readableByteCount();
                totals[1] += text.lines().count();
                totals[2]++;
                assertTrue(buffer.readableByteCount() < 300_000);
                DataBufferUtils.release(buffer);
            }).then()).verifyComplete();
        assertEquals(200_001, totals[1]);
        assertEquals(783, totals[2]); // 表头 + ceil(200000/256)。
        assertTrue(totals[0] > 40_000_000);
        assertEquals(200_000, content.orderIds().size());
        assertEquals("order-199999", content.orderIds().get(199_999));
    }

    @Test void concurrentGrowthPastLimitFailsAtStreamingOwner() {
        var content = new RepairExportContent();
        StepVerifier.create(content.encode(Flux.range(0, 200_001).map(RepairExportStreamingTest::order))
            .doOnNext(DataBufferUtils::release).then())
            .expectErrorSatisfies(error -> {
                assertInstanceOf(BusinessException.class, error);
                assertEquals("error.repair.export_limit", error.getMessage());
            }).verify();
        assertEquals(200_000, content.orderIds().size());
    }

    @Test void sourceFailureIsPropagatedInsteadOfCompletingPartialFile() {
        var failure = new IllegalStateException("query connection lost");
        var content = new RepairExportContent();
        StepVerifier.create(content.encode(Flux.concat(Flux.just(order(0)), Flux.error(failure)))
            .doOnNext(DataBufferUtils::release).then()).expectErrorMatches(error -> error == failure).verify();
    }

    @Test void downloadChecksAllBatchesBeforeReturningTaskAndStopsOnRevokedOrder() {
        var contexts=org.mockito.Mockito.mock(RepairContextProvider.class);
        var visibility=org.mockito.Mockito.mock(RepairExportScope.class);
        var holder=org.mockito.Mockito.mock(AssetsHolder.class);
        var context=new RepairContext("tenant","app","employee","员工");
        org.mockito.Mockito.when(contexts.current()).thenReturn(Mono.just(context));
        org.mockito.Mockito.when(visibility.holder(context)).thenReturn(Mono.just(holder));
        var service = new RepairExportService(contexts, null, null, null, null, null, null, visibility);
        var task = new RepairExportEntity();
        task.setStatus("completed");
        task.setOrderIds(IntStream.range(0, 1201).mapToObj(index -> "order-" + index).toList());
        var batches = new ArrayList<List<String>>();
        org.mockito.Mockito.doAnswer(call -> {
                    List<String> ids = List.copyOf(call.<Collection<String>>getArgument(1));
                    batches.add(ids);
                    return ids.contains("order-1000") ? Mono.error(new AccessDenyException()) : Mono.empty();
                }).when(holder).assertPermission0(eq(RepairAssetType.repairOrder),any(Collection.class),eq(CrudAssetPermission.read));
            StepVerifier.create(service.authorizeDownload(task)).expectError(AccessDenyException.class).verify();
            assertEquals(List.of(500, 500, 201), batches.stream().map(List::size).toList());
            assertEquals(task.getOrderIds(), batches.stream().flatMap(List::stream).toList());
    }

    private static RepairOrderEntity order(int index) {
        var order = new RepairOrderEntity();
        order.setId("order-" + index);
        order.setNumber("BS-" + index);
        order.setStatus(0);
        order.setBusinessProjectName("压力验证项目");
        order.setDescription("水管漏水待处理，保留完整报事内容。".repeat(5));
        order.setContactName("测试报事人");
        order.setContactPhone("13800000000");
        return order;
    }
}
