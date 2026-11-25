UPDATE metric_item m
JOIN metric_category c ON c.id=m.category_id AND c.category_code='businessValue'
SET m.metric_name='告警处理率',
    m.metric_definition='已处理告警量占全部告警量的比例'
WHERE BINARY m.metric_code='alarmHandleRate';

UPDATE alarm_strategy s
JOIN metric_item m ON s.target_type='metric' AND s.target_id=m.id
JOIN metric_category c ON c.id=m.category_id AND c.category_code='businessValue'
SET s.target_name='告警处理率',
    s.strategy_name=REPLACE(s.strategy_name,'告警处理及时率','告警处理率')
WHERE BINARY m.metric_code='alarmHandleRate';
