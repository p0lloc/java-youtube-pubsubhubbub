package cc.pollo.pubsubcallback;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Data object for the video feed sent to the callback server
 */
@Builder
@Getter
public class VideoFeed {

    private final String channelId;
    private final String videoId;
    private final String title;
    private final String link;
    private final String author;

    private final Instant datePublished;
    private final Instant dateUpdated;

    @Setter
    private boolean newVideo;

}
