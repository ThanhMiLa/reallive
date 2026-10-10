package com.reallive.notificationservice.email.entity;

public enum EmailDeliveryEventType {

    /** SendGrid accepted the message and prepared it for delivery. */
    PROCESSED,
    /** The recipient's mail server accepted the message; this does not confirm that it was read. */
    DELIVERED,
    /** Delivery was temporarily delayed; SendGrid may retry. */
    DEFERRED,
    /** The recipient's mail server rejected the message, permanently or temporarily. */
    BOUNCE,
    /** SendGrid stopped the message before attempting delivery, for example due to suppression. */
    DROPPED;

    /** Returns the supported delivery type, or null for an unrelated or unknown provider event. */
    public static EmailDeliveryEventType fromSendGridEvent(String event) {
        if (event == null) {
            return null;
        }
        return switch (event) {
            case "processed" -> PROCESSED;
            case "delivered" -> DELIVERED;
            case "deferred" -> DEFERRED;
            case "bounce" -> BOUNCE;
            case "dropped" -> DROPPED;
            default -> null;
        };
    }
}
