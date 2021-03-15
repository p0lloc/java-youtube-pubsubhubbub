# java-youtube-pubsubhubbub

This is a simple callback server for YouTube Pubsubhubbub notifications.  
Here is an example how to use this:

```java
int port = 8080;
int leaseSeconds = 60 * 60 * 24 * 7;

YouTubePubSubCallback callbackServer = new YouTubePubSubCallback();
callbackServer.subscribe("http://example.com", "UCJhjE7wbdYAae1G25m0tHAA", leaseSeconds);
callbackServer.start(feed -> {
    if(feed.isNewVideo()){
        System.out.println("NEW VIDEO!");
    }

    System.out.println(feed.getVideoId());
    System.out.println(feed.getChannelId());
    System.out.println(feed.getTitle());
    System.out.println(feed.getLink());
    System.out.println(feed.getAuthor());
}, port);
```

Note that maximum lease time seems to be 10 days, this will require you to create your own re-subscription scheduler.
