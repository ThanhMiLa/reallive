package com.reallive.streamservice.stream.adapter.media;

public record MediaPathConfiguration(
        String name,
        String source,
        boolean overridePublisher
) {
    public boolean acceptsPublisher() {
        return "publisher".equals(source) && !overridePublisher;
    }
}
