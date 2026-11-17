package org.jetlinks.pro.patrol.entity;

import jakarta.validation.constraints.*;

/** 客户端报告的定位证据；仅用于辅助提醒，demo 不能作为真实到场凭证。 */
public final class PatrolLocation {
    private PatrolLocation() {}
    public enum Source { device, demo }
    public enum CoordinateSystem { WGS84 }

    public record Evidence(
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("0") @DecimalMax("100000") Double accuracy,
            @NotNull @Positive Long collectedTime,
            @Size(max = 255) String address,
            @NotNull CoordinateSystem coordinateSystem,
            @NotNull Source source) {}

    public record Reference(Double longitude, Double latitude, String address, int rangeMeters) {}
    public record Snapshot(Evidence evidence, Reference reference, Double distanceMeters, Boolean withinRange) {}

    public static Reference reference(PatrolPointEntity point, Integer range) {
        return new Reference(point.getLongitude(), point.getLatitude(),
                point.getLocationAddress(), range == null ? 100 : range);
    }

    /** 缺少点位坐标时只对明确的模拟样本使用演示中心，绝不补写点位档案。 */
    public static Snapshot snapshot(Evidence evidence, Reference reference) {
        if (evidence == null) return null;
        if ((reference.longitude() == null || reference.latitude() == null) && evidence.source() == Source.demo)
            reference = new Reference(106.551556, 29.563009, reference.address(), reference.rangeMeters());
        if (reference.longitude() == null || reference.latitude() == null)
            return new Snapshot(evidence, reference, null, null);
        double lat = Math.toRadians(evidence.latitude() - reference.latitude());
        double lng = Math.toRadians(evidence.longitude() - reference.longitude());
        double a = Math.pow(Math.sin(lat / 2), 2) + Math.cos(Math.toRadians(reference.latitude()))
                * Math.cos(Math.toRadians(evidence.latitude())) * Math.pow(Math.sin(lng / 2), 2);
        double distance = Math.round(6371008.8 * 2 * Math.asin(Math.sqrt(Math.min(1, a))) * 10) / 10.0;
        return new Snapshot(evidence, reference, distance, distance <= reference.rangeMeters());
    }
}
