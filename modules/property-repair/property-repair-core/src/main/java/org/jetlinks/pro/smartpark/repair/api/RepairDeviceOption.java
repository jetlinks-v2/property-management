package org.jetlinks.pro.smartpark.repair.api;
/** 报修选择器的只读设备投影，不暴露设备认证配置或连接密钥。 */
public record RepairDeviceOption(String id,String name,String productId,String productName) {}
