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
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles processing YouTube searches.
 */
public class YoutubeChannelProvider implements YoutubeChannelLoader {
  private static final Logger log = LoggerFactory.getLogger(YoutubeChannelProvider.class);

  private final HttpInterfaceManager httpInterfaceManager;
  private static final Pattern polymerInitialDataRegex = Pattern.compile("(window\\[\"ytInitialData\"]|var ytInitialData)\\s*=\\s*(.*);");
  private static final Pattern channelUrlRegex = Pattern.compile("^https?://(?:www\\.|m\\.|music\\.|)youtube\\.com/(?:user|channel|c)/.*");

  public YoutubeChannelProvider() {
    this.httpInterfaceManager = HttpClientTools.createCookielessThreadLocalManager();
  }

  public ExtendedHttpConfigurable getHttpConfiguration() {
    return httpInterfaceManager;
  }

  @Override
  public List<YoutubeChannel> loadSearchResult(String query) {
    try {
      if (channelUrlRegex.matcher(query).matches()) {
        return doRequest(new URIBuilder(query).build(), this::extractChannelResult);
      }
      return doRequest(
          new URIBuilder("https://www.youtube.com/results")
          .addParameter("search_query", query).build(),
          this::extractSearchResults);
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  private List<YoutubeChannel> doRequest(URI url, Function<Document, List<YoutubeChannel>> extractor) throws IOException {
    try (HttpInterface httpInterface = httpInterfaceManager.getInterface()) {
      try (CloseableHttpResponse response = httpInterface.execute(new HttpGet(url))) {
        int statusCode = response.getStatusLine().getStatusCode();
        if (!HttpClientTools.isSuccessWithContent(statusCode)) {
          throw new IOException("Invalid status code for search response: " + statusCode);
        }

        Document document = Jsoup.parse(response.getEntity().getContent(), StandardCharsets.UTF_8.name(), "");
        return extractor.apply(document);
      }
    }
  }

  private List<YoutubeChannel> extractChannelResult(Document document) {
    log.debug("Attempting to parse results page as polymer");
    Element body = document.body();

    String id = selectMeta(body,"meta[itemprop=channelId]");
    String title = selectMeta(body,"meta[name=title]");

    if (id == null || title == null) {
      throw new IllegalStateException("Can't get channel info");
    }

    String description = selectMeta(body,"meta[name=description]");
    String thumbnail = selectMeta(body,"meta[name=twitter:image]");

    return Collections.singletonList(new YoutubeChannel(id, title, description, thumbnail));
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

    JsonBrowser jsonBrowser = JsonBrowser.parse(matcher.group(2));
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

  private String selectMeta(Element body, String query) {
    Element element = body.select(query).first();
    return element != null ? element.attr("content") : null;
  }
}
