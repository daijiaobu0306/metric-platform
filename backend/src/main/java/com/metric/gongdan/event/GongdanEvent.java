package com.metric.gongdan.event;

import com.metric.gongdan.entity.Gongdan;
import org.springframework.context.ApplicationEvent;

public class GongdanEvent extends ApplicationEvent {

    public static final String TYPE_CREATED = "CREATED";
    public static final String TYPE_CLOSED = "CLOSED";

    private final Gongdan gongdan;
    private final String eventType;

    public GongdanEvent(Object source, Gongdan gongdan, String eventType) {
        super(source);
        this.gongdan = gongdan;
        this.eventType = eventType;
    }

    public Gongdan getGongdan() {
        return gongdan;
    }

    public String getEventType() {
        return eventType;
    }
}
