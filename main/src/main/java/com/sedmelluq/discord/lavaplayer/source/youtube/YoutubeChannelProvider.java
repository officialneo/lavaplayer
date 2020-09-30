package com.sedmelluq.discord.lavaplayer.source.youtube;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.http.ExtendedHttpConfigurable;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles processing YouTube searches.
 */
public class YoutubeChannelProvider implements YoutubeChannelLoader {
  private static final Logger log = LoggerFactory.getLogger(YoutubeChannelProvider.class);

  private final HttpInterfaceManager httpInterfaceManager;
  private final Pattern polymerInitialDataRegex = Pattern.compile("window\\[\"ytInitialData\"]\\s*=\\s*(.*);+\\n");

  public YoutubeChannelProvider() {
    this.httpInterfaceManager = HttpClientTools.createCookielessThreadLocalManager();
  }

  public ExtendedHttpConfigurable getHttpConfiguration() {
    return httpInterfaceManager;
  }

  @Override
  public List<YoutubeChannel> loadSearchResult(String query) {
    log.debug("Performing a search with query {}", query);

    try (HttpInterface httpInterface = httpInterfaceManager.getInterface()) {
      URI url = new URIBuilder("https://www.youtube.com/results").addParameter("search_query", query).build();

      try (CloseableHttpResponse response = httpInterface.execute(new HttpGet(url))) {
        int statusCode = response.getStatusLine().getStatusCode();
        if (!HttpClientTools.isSuccessWithContent(statusCode)) {
          throw new IOException("Invalid status code for search response: " + statusCode);
        }

        Document document = Jsoup.parse(response.getEntity().getContent(), StandardCharsets.UTF_8.name(), "");
        return extractSearchResults(document);
      }
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  private List<YoutubeChannel> extractSearchResults(Document document) {
    log.debug("Attempting to parse results page as polymer");
    try {
      return polymerExtractChannels(document);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private List<YoutubeChannel> polymerExtractChannels(Document document) throws IOException {
    // Match the JSON from the HTML. It should be within a script tag
    Matcher matcher = polymerInitialDataRegex.matcher(document.outerHtml());
    if (!matcher.find()) {
      log.warn("Failed to match ytInitialData JSON object");
      return Collections.emptyList();
    }

    JsonBrowser jsonBrowser = JsonBrowser.parse(matcher.group(1));
    ArrayList<YoutubeChannel> list = new ArrayList<>();
    jsonBrowser.get("contents")
        .get("twoColumnSearchResultsRenderer")
        .get("primaryContents")
        .get("sectionListRenderer")
        .get("contents")
        .index(0)
        .get("itemSectionRenderer")
        .get("contents")
        .values()
        .forEach(json -> {
          YoutubeChannel channel = extractPolymerData(json);
          if (channel != null) list.add(channel);
        });
    return list;
  }

  private YoutubeChannel extractPolymerData(JsonBrowser json) {
    json = json.get("channelRenderer");
    if (json.isNull()) return null; // Ignore everything which is not a channel

    String id = json.get("channelId").text();
    String title = json.get("title").get("simpleText").text();
    String description = json.get("descriptionSnippet").get("runs").index(0).get("text").text();

    JsonBrowser thumbnailData = json.get("thumbnail").get("thumbnails").values().stream()
        .max(Comparator.comparingLong(e -> e.get("width").asLong(0)))
        .orElse(null);
    String thumbnail = null;
    if (thumbnailData != null && !thumbnailData.isNull()) {
      thumbnail = thumbnailData.get("url").text();
    }
    if (thumbnail != null && thumbnail.startsWith("//")) {
      thumbnail = "https:" + thumbnail;
    }

    return new YoutubeChannel(id, title, description, thumbnail);
  }
}
