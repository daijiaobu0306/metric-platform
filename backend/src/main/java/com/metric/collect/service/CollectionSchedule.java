package com.metric.collect.service;

import java.time.*;

/** Execution time is independent of the original business date in each input record. */
final class CollectionSchedule {
    private CollectionSchedule() {}
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static boolean validTime(String value) {
        return value!=null && value.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]");
    }

    static boolean daily(CollectionSource source) {
        return source.schedule()!=null && "daily".equals(source.schedule().type());
    }

    static void validate(CollectionSource source) {
        var schedule=source.schedule();
        if(schedule!=null && !"interval".equals(schedule.type()) && !"daily".equals(schedule.type()))
            throw new IllegalArgumentException("调度方式须为按间隔执行或每日定时");
        if(daily(source)) {
            if(!validTime(schedule.time()))
                throw new IllegalArgumentException("每日执行时间须为北京时间HH:mm，例如08:05");
        } else if(source.intervalSeconds()<(source.usesIngestionTime()?5:10) || source.intervalSeconds()>604800) {
            throw new IllegalArgumentException("扫描间隔须为"+(source.usesIngestionTime()?5:10)+"～604800秒");
        }
        var arrival=source.arrival();
        if(arrival!=null && (arrival.lagDays()==null || arrival.lagDays()<0 || arrival.lagDays()>365 || !validTime(arrival.dueTime())))
            throw new IllegalArgumentException("应到规则须配置T-0至T-365和北京时间HH:mm");
        if(schedule!=null && (schedule.retryUntil()!=null || schedule.retryIntervalMinutes()!=null)) {
            if(!daily(source) || arrival==null || !validTime(schedule.retryUntil())
                    || !LocalTime.parse(schedule.retryUntil()).isAfter(LocalTime.parse(schedule.time()))
                    || LocalTime.parse(schedule.time()).isBefore(LocalTime.parse(arrival.dueTime()))
                    || schedule.retryIntervalMinutes()==null || schedule.retryIntervalMinutes()<1 || schedule.retryIntervalMinutes()>60)
                throw new IllegalArgumentException("补采须配置应到规则；开始时间不早于应到时间，截止晚于开始且限同日，间隔1～60分钟");
        }
    }

    static LocalDateTime initial(CollectionSource source,LocalDateTime now) {
        return daily(source)?next(source,now):now;
    }

    static LocalDateTime next(CollectionSource source,LocalDateTime now) {
        if(!daily(source)) return now.plusSeconds(source.intervalSeconds());
        var candidate=now.toLocalDate().atTime(LocalTime.parse(source.schedule().time()));
        return candidate.isAfter(now)?candidate:candidate.plusDays(1);
    }

    static LocalDate expectedDate(CollectionSource source,LocalDateTime now) {
        return source.arrival()==null?null:now.toLocalDate().minusDays(source.arrival().lagDays());
    }

    static LocalDateTime afterRun(CollectionSource source,LocalDateTime now,LocalDate latest,boolean failed) {
        var regular=next(source,now);
        if(!daily(source) || source.schedule().retryUntil()==null || source.arrival()==null) return regular;
        var start=now.toLocalDate().atTime(LocalTime.parse(source.schedule().time()));
        var end=now.toLocalDate().atTime(LocalTime.parse(source.schedule().retryUntil()));
        if(now.isBefore(start) || !now.isBefore(end)) return regular;
        var expected=expectedDate(source,now);
        if(!failed && latest!=null && !latest.isBefore(expected)) return regular;
        var retry=now.plusMinutes(source.schedule().retryIntervalMinutes());
        return !retry.isAfter(end)?retry:regular;
    }

    static LocalDateTime afterConfigurationError(CollectionSource source,LocalDateTime now) {
        try { validate(source);return next(source,now); }
        catch(IllegalArgumentException e) { return now.plusMinutes(5); }
    }
}
