package com.reallive.streamservice.stream.adapter.media.dto;

public record MediaPathConfiguration(
        String name,
        String source,
        boolean overridePublisher,
        boolean sourceOnDemand,
        String runOnOnline,
        String runOnOffline
) {
    public MediaPathConfiguration(String name, String source, boolean overridePublisher) {
        this(name, source, overridePublisher, false, null, null);
    }

    public boolean acceptsPublisher() {
        return "publisher".equals(source) && !overridePublisher;
    }

    public boolean acceptsPlaybackRelay(String expectedSourceUrl) {
        return expectedSourceUrl != null
                && expectedSourceUrl.equals(source)
                && !sourceOnDemand
                && (runOnOnline == null || runOnOnline.isEmpty())
                && (runOnOffline == null || runOnOffline.isEmpty());
    }

    @Override
    public String toString() {
        return "MediaPathConfiguration[name="
                + name
                + ", overridePublisher="
                + overridePublisher
                + ", sourceOnDemand="
                + sourceOnDemand
                + "]";
    }
}
