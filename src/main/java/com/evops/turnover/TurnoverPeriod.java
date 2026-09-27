package com.evops.turnover;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 统计周期解析（turnover-1）：统一半开区间 [start, end)。
 * DAY 自然日；WEEK ISO 周（周一起）；MONTH 自然月。
 */
public final class TurnoverPeriod {

    public static final String DAY = "DAY";
    public static final String WEEK = "WEEK";
    public static final String MONTH = "MONTH";

    private TurnoverPeriod() {}

    /** 返回 [起点, 终点)，anchor 为空取当天 */
    public static LocalDateTime[] range(String periodType, LocalDate anchor) {
        String type = normalize(periodType);
        LocalDate date = anchor == null ? LocalDate.now() : anchor;
        LocalDate startDate;
        LocalDate endDate;
        switch (type) {
            case DAY:
                startDate = date;
                endDate = date.plusDays(1);
                break;
            case WEEK:
                startDate = date.with(DayOfWeek.MONDAY);
                endDate = startDate.plusWeeks(1);
                break;
            default: // MONTH
                startDate = date.withDayOfMonth(1);
                endDate = startDate.plusMonths(1);
                break;
        }
        return new LocalDateTime[]{
                LocalDateTime.of(startDate, LocalTime.MIDNIGHT),
                LocalDateTime.of(endDate, LocalTime.MIDNIGHT)};
    }

    public static String normalize(String periodType) {
        if (periodType == null) {
            throw new IllegalArgumentException("周期类型不能为空（DAY/WEEK/MONTH）");
        }
        String t = periodType.trim().toUpperCase();
        if (!DAY.equals(t) && !WEEK.equals(t) && !MONTH.equals(t)) {
            throw new IllegalArgumentException("非法周期类型：" + periodType + "，仅支持 DAY/WEEK/MONTH");
        }
        return t;
    }
}
