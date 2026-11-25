package com.metric.alarm.event;

import com.metric.alarm.service.AlarmChannelDispatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class AlarmEventListener {

    @Autowired
    private AlarmChannelDispatcher alarmChannelDispatcher;

    @Async
    @EventListener
    public void handleAlarmCreatedEvent(AlarmCreatedEvent event) {
        alarmChannelDispatcher.dispatch(event.getAlarm());
    }
}
