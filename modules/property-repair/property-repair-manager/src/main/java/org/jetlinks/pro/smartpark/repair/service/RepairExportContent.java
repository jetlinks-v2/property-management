package org.jetlinks.pro.smartpark.repair.service;

import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 单次导出的流式内容。仅保留下载授权所需的ID；正文每256条编码，不缓存全部工单。 */
final class RepairExportContent {
    static final int MAX_ROWS = 200_000;
    private static final int CHUNK_ROWS = 256;
    private final List<String> orderIds = new ArrayList<>();

    Flux<DataBuffer> encode(Flux<RepairOrderEntity> rows) {
        Flux<String> body = rows.map(order -> {
            // 计数预检与文件查询之间可能新增记录，写入边界仍执行同一上限。
            RepairFlowRules.require(orderIds.size() < MAX_ROWS, "export_limit");
            orderIds.add(order.getId());
            return RepairCsv.row(order);
        }).buffer(CHUNK_ROWS).map(chunk -> String.join("", chunk));
        return Flux.concat(Flux.just(RepairCsv.header()), body)
            .map(text -> DefaultDataBufferFactory.sharedInstance.wrap(text.getBytes(StandardCharsets.UTF_8)));
    }

    List<String> orderIds() {
        return orderIds;
    }
}
