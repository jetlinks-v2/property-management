package org.jetlinks.pro.property.finance.service;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.*;
import org.hswebframework.web.authorization.simple.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.report.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.transaction.ReactiveTransactionManager;
import reactor.core.publisher.*;
import reactor.util.context.Context;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes=FinanceExportPersistenceTest.Config.class,webEnvironment=SpringBootTest.WebEnvironment.NONE)
class FinanceExportPersistenceTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){InvoiceCompanyPersistenceTest.database(r);}
    @Autowired FinanceScope scope;
    @Autowired ReportExportPages pages;
    @Autowired ReactiveTransactionManager transactions;
    @Autowired ReactiveRepository<FinanceExportTaskEntity,String> tasks;
    @Autowired ReactiveRepository<ReceivableEntity,String> bills;
    @TempDir Path directory;
    CarryForwardAuthorityTest authority;
    SimpleAuthentication executor;
    FinanceExportJobs jobs;
    String project;
    static final Duration WAIT=Duration.ofSeconds(90);
    @BeforeEach void setup(){
        project=UUID.randomUUID().toString();authority=new CarryForwardAuthorityTest();authority.holders();authority.rights.put("saved",Set.of("room",project));executor=authority.auth("saved");executor.setPermissions(List.of(SimplePermission.builder().id("property-finance-report-receivable").actions(Set.of("query","export")).build()));
        jobs=new FinanceExportJobs(scope,tasks,pages,transactions,directory.toString(),id->Mono.just(executor));
    }
    @AfterEach void close(){jobs.stop();authority.close();}
    Context actor(){return new HouseSettingPersistenceTest().allowed().put(Authentication.class,executor).put(org.jetlinks.pro.assets.AssetsHolder.class,org.jetlinks.pro.assets.AssetsHolder.fromAuth(executor,org.jetlinks.pro.assets.scope.AssetScopeContext.current(Context.empty())).block(WAIT));}
    ReceivableReportInput input(){var i=new ReceivableReportInput();i.setProjectIds(List.of(project));i.setGroupBy("PROJECT");i.setExportMode("BOTH");return i;}
    ReceivableEntity bill(){var f=new ReceivableReportPersistenceTest();f.project=project;f.bills=bills;return f.bill("1","0");}
    @Test void backgroundWorkbookIncludesMoreThanTenThousandRowsAndKeepsIdentifiersText() throws Exception {
        var b=bill();var batch=new ArrayList<ReceivableEntity>();
        for(int n=0;n<10000;n++){var row=new ReceivableEntity();org.springframework.beans.BeanUtils.copyProperties(b,row);row.setId(UUID.randomUUID().toString());row.setBillNo("YS20261109"+String.format("%016d",n));row.setBillKey(UUID.randomUUID().toString());batch.add(row);}
        Flux.fromIterable(batch).buffer(500).concatMap(group->bills.insertBatch(Mono.just(group))).then().block(WAIT);
        var task=jobs.create("receivables",input(),"excel").contextWrite(actor()).block(WAIT);assertEquals("QUEUED",task.getState());
        jobs.poll().block(WAIT);var status=jobs.status("receivables",task.getId()).contextWrite(actor()).block(WAIT);assertEquals("DONE",status.getState(),status.getErrorMessage());
        var resource=jobs.download("receivables",task.getId()).contextWrite(actor()).block(WAIT);
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(resource.getInputStream())){
            assertEquals(3,book.getNumberOfSheets());assertEquals(10002,book.getSheetAt(1).getLastRowNum());
            assertEquals(org.apache.poi.ss.usermodel.CellType.STRING,book.getSheetAt(1).getRow(1).getCell(4).getCellType());
            int net=-1;for(var cell:book.getSheetAt(1).getRow(0))if(cell.getStringCellValue().contains("净应收"))net=cell.getColumnIndex();assertTrue(net>=0);assertEquals(10001.0,book.getSheetAt(1).getRow(10002).getCell(net).getNumericCellValue());
        }
        // 导出后空间权限撤销，文件缓存不能继续扩大可见范围。
        authority.rights.put("saved",Set.of(project));
        assertThrows(RuntimeException.class,()->jobs.download("receivables",task.getId()).contextWrite(actor()).block(WAIT));
    }
    @Test void revokedExportPermissionFailsVisiblyAndOtherOwnerCannotReadStatus(){
        bill();var task=jobs.create("receivables",input(),"excel").contextWrite(actor()).block(WAIT);
        executor.setPermissions(List.of());jobs.poll().block(WAIT);
        assertEquals("FAILED",tasks.findById(task.getId()).block(WAIT).getState());
        var other=authority.auth("saved");((SimpleUser)other.getUser()).setId("other-user");
        assertThrows(RuntimeException.class,()->jobs.status("receivables",task.getId()).contextWrite(actor().put(Authentication.class,other)).block(WAIT));
    }
    @Configuration @Import({ReceivableReportPersistenceTest.Config.class,ReportExportPages.class})
    static class Config {
        @Bean FinanceAnalysisService analysis(){return mock(FinanceAnalysisService.class);}
        @Bean ReceivedReportService received(){return mock(ReceivedReportService.class);}
        @Bean InvoiceReportService invoices(){return mock(InvoiceReportService.class);}
    }
}
