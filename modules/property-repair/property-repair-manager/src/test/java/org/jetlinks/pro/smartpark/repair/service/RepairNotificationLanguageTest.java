package org.jetlinks.pro.smartpark.repair.service;

import org.hswebframework.web.i18n.LocaleUtils;
import org.hswebframework.web.i18n.MessageSourceInitializer;
import org.jetlinks.pro.smartpark.repair.entity.RepairNotificationEntity;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import org.jetlinks.pro.smartpark.repair.entity.RepairProcessEntity;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 请求语言跨异步查库、持久化与后台投递后，标题和正文仍保持一致。 */
class RepairNotificationLanguageTest {
    @Test
    void notificationLanguageFollowsTheRequestAcrossAsyncWorkAndDelivery() throws Exception {
        // 平台 MessageSource 只能初始化一次，使用独立 JVM 避免改变其他单元测试的全局环境。
        var process = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Duser.language=en", "-Duser.country=US", "-cp",
            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            getClass().getName()
        ).inheritIO().start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "notification language check timed out");
            assertEquals(0, process.exitValue(), "notification language check failed");
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    public static void main(String[] args) {
        var source = new ResourceBundleMessageSource();
        source.setBasename("i18n/smart-park-repair/messages");
        source.setDefaultEncoding("UTF-8");
        MessageSourceInitializer.init(source);
        requestLanguageSurvivesAsyncWork(Locale.SIMPLIFIED_CHINESE);
        requestLanguageSurvivesAsyncWork(Locale.US);
        everyActionUsesTheCapturedLanguage();
        legacyMessagesRetainTheirOriginalContent();
    }

    private static void requestLanguageSurvivesAsyncWork(Locale locale) {
        var order = order();
        order.setAssignedUserId("worker");
        var orders = mock(RepairOrderService.class);
        var store = mock(RepairNotificationStore.class);
        var roles = mock(RepairRoleService.class);
        var snapshot = new AtomicReference<RepairNotificationEntity>();
        when(orders.findById("order")).thenReturn(Mono.just(order).publishOn(Schedulers.parallel()));
        when(roles.permitted(eq("worker"), eq("query"), same(order), any(RepairContext.class)))
            .thenReturn(Mono.just(true));
        when(store.insert(any(RepairNotificationEntity.class))).thenAnswer(call -> {
            snapshot.set(call.getArgument(0));
            return Mono.just(1);
        });
        var service = new RepairNotificationService(store, orders, mock(RepairConfigurationService.class),
            mock(RepairReviewService.class), mock(RepairDirectory.class), roles);
        StepVerifier.create(service.record(process("dispatch")).contextWrite(LocaleUtils.useLocale(locale)))
            .verifyComplete();
        var entry = snapshot.get();
        assertNotNull(entry);
        boolean chinese = "zh".equals(locale.getLanguage());
        assertEquals(chinese ? "[业务项目]工单BS-EXAMPLE：已分派，请接单"
            : "[业务项目] Order BS-EXAMPLE: Assigned. Please accept the order", entry.getMessage());
        assertEquals(locale.toLanguageTag(), entry.getDetail().get("locale"));
        // 投递发生在另一个请求或后台线程时，必须使用已保存的语言。
        LocaleUtils.doWith(chinese ? Locale.US : Locale.SIMPLIFIED_CHINESE, ignored -> {
            var command = RepairNotificationService.command(entry);
            assertEquals(chinese ? "报事报修通知" : "Issue and repair notifications", command.getTopicName());
            assertEquals(entry.getMessage(), command.getMessage());
            assertEquals("dispatch", command.getCode());
        });
    }

    private static void everyActionUsesTheCapturedLanguage() {
        Map<String, String[]> actions = Map.ofEntries(
            Map.entry("create", new String[]{"新报事待处理", "New issue awaiting handling"}),
            Map.entry("autoDispatch", new String[]{"自动分派至待接单池", "Automatically assigned to the acceptance pool"}),
            Map.entry("dispatch", new String[]{"已分派，请接单", "Assigned. Please accept the order"}),
            Map.entry("transfer", new String[]{"已转派，请接单", "Transferred. Please accept the order"}),
            Map.entry("accept", new String[]{"已接单", "Order accepted"}),
            Map.entry("grab", new String[]{"已抢单", "Order claimed"}),
            Map.entry("follow", new String[]{"新增跟进记录", "New follow-up record"}),
            Map.entry("finish", new String[]{"已完成，请处理后续回访或审核", "Completed. Continue with the follow-up visit or review"}),
            Map.entry("callback", new String[]{"已回访", "Follow-up visit completed"}),
            Map.entry("review", new String[]{"审核结果已更新", "Review result updated"}),
            Map.entry("quote", new String[]{"费用待签字", "Costs awaiting signature"}),
            Map.entry("sign", new String[]{"签字已登记，可继续完成", "Signature recorded. The order can now be completed"}),
            Map.entry("close", new String[]{"非正常关单待审核", "Abnormal closure awaiting review"}),
            Map.entry("abandonApply", new String[]{"废弃申请待审核", "Discard request awaiting review"}),
            Map.entry("changeLevel", new String[]{"等级变更待审核", "Priority change awaiting review"}),
            Map.entry("delay", new String[]{"延期申请待审核", "Extension request awaiting review"}),
            Map.entry("urge", new String[]{"工单催办，请及时处理", "Order reminder. Please handle promptly"}),
            Map.entry("timeout", new String[]{"工单已超时，请及时处理", "Order overdue. Please handle promptly"})
        );
        for (Locale locale : new Locale[]{Locale.SIMPLIFIED_CHINESE, Locale.US}) {
            boolean chinese = "zh".equals(locale.getLanguage());
            LocaleUtils.doWith(chinese ? Locale.US : Locale.SIMPLIFIED_CHINESE, ignored -> actions.forEach((action, text) -> {
                var entry = RepairNotificationService.entry(process(action), order(),
                    new RepairNotificationService.Recipient("worker", null, "query"), locale);
                String expected = chinese ? "[业务项目]工单BS-EXAMPLE：" + text[0]
                    : "[业务项目] Order BS-EXAMPLE: " + text[1];
                assertEquals(expected, entry.getMessage(), action);
                var command = RepairNotificationService.command(entry);
                assertEquals(chinese ? "报事报修通知" : "Issue and repair notifications", command.getTopicName());
                assertEquals(expected, command.getMessage());
                assertEquals(action, command.getCode());
            }));
        }
    }

    private static void legacyMessagesRetainTheirOriginalContent() {
        var entry = RepairNotificationService.entry(process("dispatch"), order(),
            new RepairNotificationService.Recipient("worker", null, "query"), Locale.US);
        var detail = new HashMap<>(entry.getDetail());
        detail.remove("locale");
        entry.setDetail(detail);
        LocaleUtils.doWith(Locale.SIMPLIFIED_CHINESE, ignored -> {
            var command = RepairNotificationService.command(entry);
            assertEquals(entry.getMessage(), command.getMessage());
            assertEquals("报事报修通知", command.getTopicName());
        });
        entry.setDetail(null);
        assertEquals(entry.getMessage(), RepairNotificationService.command(entry).getMessage());
    }

    private static RepairProcessEntity process(String action) {
        var process = new RepairProcessEntity();
        process.setId("process");
        process.setOrderId("order");
        process.setTenantId("tenant");
        process.setApplicationId("application");
        process.setAction(action);
        process.setCreateTime(1234L);
        return process;
    }

    private static RepairOrderEntity order() {
        var order = new RepairOrderEntity();
        order.setId("order");
        order.setNumber("BS-EXAMPLE");
        order.setApplicationId("application");
        order.setExtension("tenantId", "tenant");
        order.setBusinessProjectId("project");
        order.setBusinessProjectName("业务项目");
        order.setStatus(6);
        return order;
    }
}
