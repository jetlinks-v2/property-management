package org.jetlinks.pro.smartpark.repair.service;

import org.jetlinks.pro.io.excel.ExcelUtils;
import org.springframework.core.io.buffer.DataBuffer;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;

/** 复用平台响应式Excel写入；仅保存下载复核ID，不收集全部实体或文件正文。 */
final class RepairExportDocument {
    private final LinkedHashSet<String> orderIds=new LinkedHashSet<>();
    private int rowCount;
    Flux<DataBuffer> encode(RepairExportType type,String format,Flux<RepairExportTables.Row> source) {
        var rows=source.map(row -> {
            RepairFlowRules.require(rowCount < RepairExportContent.MAX_ROWS,"export_limit");
            rowCount++;
            for(String id:row.orderIds()) {
                orderIds.add(id);
                RepairFlowRules.require(orderIds.size() <= RepairExportContent.MAX_ROWS,"export_limit");
            }
            return row.values();
        });
        // XLSX库的压缩/临时文件工作隔离到平台既有有界I/O线程，数据库仍沿响应式链读取。
        return ExcelUtils.write(RepairExportTables.headers(type),rows,format).subscribeOn(Schedulers.boundedElastic());
    }
    List<String> orderIds() { return new ArrayList<>(orderIds); }
    int rowCount() { return rowCount; }
}
