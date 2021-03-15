package cc.pollo.pubsubcallback;

import lombok.NonNull;
import org.w3c.dom.*;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Consumer;

import static spark.Spark.*;

public class YouTubePubSubCallback {

    private final DocumentBuilder documentBuilder;
    private final Set<String> previousVideos = new HashSet<>();

    private static final String CALLBACK_PATH = "/pubsubcallback";

    public YouTubePubSubCallback(){
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = null;

        try {
            builder = factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            e.printStackTrace();
        }

        documentBuilder = builder;
    }

    /**
     * Starts the callback HTTP server and provides feed to consumer
     * upon request
     * @param feedConsumer consumer to provide incoming feeds to
     */
    public void start(Consumer<VideoFeed> feedConsumer, int port){
        port(port);
        get(CALLBACK_PATH, (req, res) -> {
            String challenge = req.queryParams("hub.challenge");
            if(challenge != null)
                return challenge; // Return back challenge to verify subscription

            return "";
        });

        post(CALLBACK_PATH, (req, res) -> {
            String body = req.body();
            if(body == null)
                return "";

            VideoFeed document = null;
            try {
                document = parseFeedResponse(body);
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }

            if(document == null)
                return "";

            String videoId = document.getVideoId();
            if(!previousVideos.contains(videoId)) {
                previousVideos.add(videoId);
            } else {
                document.setNewVideo(false);
            }

            feedConsumer.accept(document);
            return "";
        });
    }

    /**
     * Subscribes to PubSub notifications
     * @param callbackUrl callback url to send feed updates to
     * @param channelId channel ID to subscribe to (starts with UC)
     */
    public void subscribe(String callbackUrl, String channelId, long leaseSeconds){
        try {
            Map<String, String> params = Map.of(
                    "hub.callback", callbackUrl + CALLBACK_PATH,
                    "hub.topic", "https://www.youtube.com/xml/feeds/videos.xml?channel_id=" + channelId,
                    "hub.verify", "async",
                    "hub.mode", "subscribe",
                    "hub.verify_token", "",
                    "hub.secret", "",
                    "hub.lease_seconds", String.valueOf(leaseSeconds));

            doHttpFormRequest("https://pubsubhubbub.appspot.com/subscribe?", "POST", params);
        } catch (IOException ignored){ }
    }

    /**
     * Parses raw XML string into VideoFeed
     * @param text raw xml string
     * @return parsed feed
     */
    private VideoFeed parseFeedResponse(String text) throws UnsupportedEncodingException {
        if(text.isEmpty() || documentBuilder == null)
            return null;

        ByteArrayInputStream input = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        VideoFeed feed = null;
        try {
            Document document = documentBuilder.parse(input);
            Element rootElement = document.getDocumentElement();

            NodeList entryList = rootElement.getElementsByTagName("entry");
            if(entryList.getLength() < 1)
                return null;

            Node entryNode = entryList.item(0);
            if(entryNode.getNodeType() != Node.ELEMENT_NODE)
                return null;

            Element entryElement = (Element) entryNode;

            String videoId   = getElementTextContent(entryElement, "yt:videoId");
            String channelId = getElementTextContent(entryElement, "yt:channelId");
            String title     = getElementTextContent(entryElement, "title");

            NodeList linkNodes = entryElement.getElementsByTagName("link");
            String link = "";

            // Find link tag with href attribute
            for (int i = 0; i < linkNodes.getLength(); i++) {
                Node item = linkNodes.item(i);
                Node href = item.getAttributes().getNamedItem("href");
                if(href != null){
                    link = href.getTextContent();
                    break;
                }
            }

            NodeList authorElements = entryElement.getElementsByTagName("author");
            String author = "";
            if(authorElements.getLength() > 0){
                Node authorItem = authorElements.item(0);

                if(authorItem.getNodeType() != Node.ELEMENT_NODE)
                    return null;

                Element authorElement = (Element) authorItem;
                author = getElementTextContent(authorElement, "name");
            }

            String datePublished = getElementTextContent(entryElement, "published");
            String dateUpdated   = getElementTextContent(entryElement, "updated");

            Instant zdtPublished = ZonedDateTime.parse(datePublished).toInstant();
            Instant zdtUpdated   = ZonedDateTime.parse(dateUpdated).toInstant();

            long diff = zdtUpdated.getEpochSecond() - zdtPublished.getEpochSecond();

            feed = VideoFeed.builder()
                    .videoId(videoId)
                    .channelId(channelId)
                    .title(title)
                    .link(link)
                    .author(author)
                    .datePublished(zdtPublished)
                    .dateUpdated(zdtUpdated)
                    .newVideo(diff < 30) // If difference is less than 30, this is most likely a new video
                    .build();

        } catch (SAXException | IOException e) {
            e.printStackTrace();
        }

        return feed;
    }

    /**
     * Gets text content of an XML child tag or empty string if not found
     * @param parentElement parent element to get child XML element under
     * @param name name of child tag
     * @return Text content or empty string if not found
     */
    @NonNull
    private String getElementTextContent(@NonNull Element parentElement, String name){
        NodeList elements = parentElement.getElementsByTagName(name);

        if(elements.getLength() < 1)
            return "";

        Node item = elements.item(0);
        if(item == null)
            return "";

        String textContent = "";
        try {
            textContent = item.getTextContent();
        } catch (DOMException ignored){ }

        return textContent;
    }

    /**
     * Makes a url encoded form HTTP request
     * @param urlStr main URL to send to
     * @param method HTTP method to use
     * @param params query parameters / form parameters
     */
    private void doHttpFormRequest(String urlStr, String method, Map<String, String> params) throws IOException {
        URL url = new URL(urlStr);
        URLConnection con = url.openConnection();
        HttpURLConnection http = (HttpURLConnection) con;
        http.setRequestMethod(method); // PUT is another valid option
        http.setDoOutput(true);

        StringJoiner sj = new StringJoiner("&");
        for(Map.Entry<String,String> entry : params.entrySet())
            sj.add(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                    + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));

        byte[] out = sj.toString().getBytes(StandardCharsets.UTF_8);
        int length = out.length;
        http.setFixedLengthStreamingMode(length);
        http.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        http.connect();

        try(OutputStream os = http.getOutputStream()) {
            os.write(out);
        }
    }

}