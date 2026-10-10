UPDATE email_delivery_events
SET event_type = UPPER(event_type)
WHERE event_type IN ('processed', 'delivered', 'deferred', 'bounce', 'dropped');
