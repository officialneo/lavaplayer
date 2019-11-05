package com.sedmelluq.discord.lavaplayer.source.yamusic;

import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.DataFormatTools;
import com.sedmelluq.discord.lavaplayer.tools.ExceptionTools;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.*;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.FAULT;
import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.SUSPICIOUS;

/**
 * Audio source manager that implements finding Yandex Music tracks based on URL.
 */
public class YandexMusicAudioSourceManager implements AudioSourceManager, HttpConfigurable {
  private static final String TRACK_URL_REGEX = "^https?://music\\.yandex\\.[a-zA-Z]+/album/([0-9]+)/track/([0-9]+)$";
  private static final String ALBUM_URL_REGEX = "^https?://music\\.yandex\\.[a-zA-Z]+/album/([0-9]+)$";
  private static final String PLAYLIST_URL_REGEX = "^https?://music\\.yandex\\.[a-zA-Z]+/users/(.+)/playlists/([0-9]+)$";

  private static final String TRACKS_INFO_FORMAT = "https://api.music.yandex.net/tracks?trackIds=";
  private static final String ALBUM_INFO_FORMAT = "https://api.music.yandex.net/albums/%s/with-tracks";
  private static final String PLAYLIST_INFO_FORMAT = "https://api.music.yandex.net/users/%s/playlists/%s";
  private static final String TRACK_DOWNLOAD_INFO = "https://api.music.yandex.net/tracks/%s/download-info";

  private static final String TRACK_URL_FORMAT = "https://music.yandex.ru/album/%s/track/%s";
  private static final String DIRECT_URL_FORMAT = "https://%s/get-%s/%s/%s%s";
  private static final String MP3_SALT = "XGRlBW9FXlekgbPrRHuSiA";

  private static final Pattern trackUrlPattern = Pattern.compile(TRACK_URL_REGEX);
  private static final Pattern albumUrlPattern = Pattern.compile(ALBUM_URL_REGEX);
  private static final Pattern playlistUrlPattern = Pattern.compile(PLAYLIST_URL_REGEX);

  private final HttpInterfaceManager httpInterfaceManager;
  private final HttpInterfaceManager httpApiInterfaceManager;

  /**
   * Create an instance.
   */
  public YandexMusicAudioSourceManager() {
    httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager();
    httpInterfaceManager.setHttpContextFilter(new YandexHttpContextFilter());
    httpApiInterfaceManager = HttpClientTools.createDefaultThreadLocalManager();
    httpInterfaceManager.setHttpContextFilter(new YandexHttpContextFilter());
  }

  @Override
  public AudioItem loadItem(DefaultAudioPlayerManager manager, AudioReference reference) {
    Matcher matcher;
    if ((matcher = trackUrlPattern.matcher(reference.identifier)).matches()) {
      return loadTrack(matcher.group(1), matcher.group(2));
    }
    if ((matcher = playlistUrlPattern.matcher(reference.identifier)).matches()) {
      return loadPlaylist(String.format(PLAYLIST_INFO_FORMAT, matcher.group(1), matcher.group(2)), "tracks");
    }
    if ((matcher = albumUrlPattern.matcher(reference.identifier)).matches()) {
      return loadPlaylist(String.format(ALBUM_INFO_FORMAT, matcher.group(1)), "volumes");
    }
    return null;
  }

  private AudioItem loadTrack(String albumId, String trackId) {
    return extractFromApi(TRACKS_INFO_FORMAT + String.format("%s:%s", trackId, albumId), (httpClient, result) -> {
      JsonBrowser entry = result.index(0);
      JsonBrowser error = entry.get("error");
      if (!error.isNull()) {
        String code = error.text();
        if ("not-found".equals(code)) {
          return new AudioReference(null, null);
        }
        throw new FriendlyException(String.format("Yandex Music returned an error code: %s", code), SUSPICIOUS, null);
      }
      return extractTrack(entry);
    });
  }

  private AudioItem loadPlaylist(String url, String trackProperty) {
    return extractFromApi(url, (httpClient, result) -> {
      JsonBrowser error = result.get("error");
      if (!error.isNull()) {
        String code = error.text();
        if ("not-found".equals(code)) {
          return new AudioReference(null, null);
        }
        throw new FriendlyException(String.format("Yandex Music returned an error code: %s", code), SUSPICIOUS, null);
      }
      JsonBrowser volumes = result.get(trackProperty);
      if (volumes.isNull()) {
        throw new FriendlyException("Empty album found", SUSPICIOUS, null);
      }
      List<AudioTrack> tracks = new ArrayList<>();
      for (JsonBrowser trackInfo : volumes.values()) {
        if (trackInfo.isList()) {
          for (JsonBrowser innerInfo : trackInfo.values()) {
            tracks.add(extractTrack(innerInfo));
          }
        } else {
          tracks.add(extractTrack(trackInfo));
        }
      }
      return new BasicAudioPlaylist(result.get("title").text(), tracks, null, false);
    });
  }

  private AudioTrack extractTrack(JsonBrowser trackInfo) {
    if (!trackInfo.get("track").isNull()) {
      trackInfo = trackInfo.get("track");
    }
    String artists = trackInfo.get("artists").values().stream()
        .map(e -> e.get("name").text())
        .collect(Collectors.joining(", "));

    String trackId = trackInfo.get("id").text();
    String albumId = trackInfo.get("albums").index(0).get("id").text();

    String artworkUrl = null;
    JsonBrowser cover = trackInfo.get("coverUri");
    if (!cover.isNull()) {
      artworkUrl = "https://" + cover.text().replace("%%", "200x200");
    }
    JsonBrowser ogImage = trackInfo.get("ogImage");
    if (!ogImage.isNull()) {
      artworkUrl = "https://" + ogImage.text().replace("%%", "200x200");
    }

    return new YandexMusicAudioTrack(new AudioTrackInfo(
        trackInfo.get("title").text(),
        artists,
        trackInfo.get("durationMs").as(Long.class),
        trackInfo.get("id").text(),
        false,
        String.format(TRACK_URL_FORMAT, albumId, trackId),
        Collections.singletonMap("artworkUrl", artworkUrl)
    ), this);
  }

  private <T> T extractFromApi(String url, ApiExtractor<T> extractor) {
    try (HttpInterface httpInterface = httpApiInterfaceManager.getInterface()) {
      String responseText;

      try (CloseableHttpResponse response = httpInterface.execute(new HttpGet(url))) {
        int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode != 200) {
          throw new IOException("Invalid status code: " + statusCode);
        }
        responseText = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
      }
      JsonBrowser response = JsonBrowser.parse(responseText);
      if (response.isNull()) {
        throw new FriendlyException("Couldn't get API response.", SUSPICIOUS, null);
      }
      response = response.get("result");
      if (response.isNull() && !response.isList()) {
        throw new FriendlyException("Couldn't get API response result.", SUSPICIOUS, null);
      }
      return extractor.extract(httpInterface, response);
    } catch (Exception e) {
      throw ExceptionTools.wrapUnfriendlyExceptions("Loading information for a Yandex Music track failed.", FAULT, e);
    }
  }

  public String extractDirectUrl(String trackId, String codec) throws IOException {
    return extractFromApi(String.format(TRACK_DOWNLOAD_INFO, trackId), (httpClient, codecsList) -> {
      JsonBrowser codecResult = codecsList.values().stream()
          .filter(e -> codec.equals(e.get("codec").text()))
          .findFirst()
          .orElse(null);
      if (codecResult == null) {
        throw new FriendlyException("Couldn't find supported track format.", SUSPICIOUS, null);
      }
      String storageUrl = codecResult.get("downloadInfoUrl").text();
      DownloadInfo info = extractDownloadInfo(storageUrl);

      String sign = DigestUtils.md5Hex(MP3_SALT + info.path.substring(1) + info.s);

      return String.format(DIRECT_URL_FORMAT, info.host, codec, sign, info.ts, info.path);
    });
  }

  private DownloadInfo extractDownloadInfo(String storageUrl) throws IOException {
    try (HttpInterface httpInterface = httpApiInterfaceManager.getInterface()) {
      String responseText;
      try (CloseableHttpResponse response = httpInterface.execute(new HttpGet(storageUrl))) {
        int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode != 200) {
          throw new IOException("Invalid status code for track storage info: " + statusCode);
        }
        responseText = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
      }
      DownloadInfo info = new DownloadInfo();
      info.host = DataFormatTools.extractBetween(responseText, "<host>", "</host>");
      info.path = DataFormatTools.extractBetween(responseText, "<path>", "</path>");
      info.ts = DataFormatTools.extractBetween(responseText, "<ts>", "</ts>");
      info.region = DataFormatTools.extractBetween(responseText, "<region>", "</region>");
      info.s = DataFormatTools.extractBetween(responseText, "<s>", "</s>");
      return info;
    } catch (Exception e) {
      throw ExceptionTools.wrapUnfriendlyExceptions("Loading information for a Yandex Music track failed.", FAULT, e);
    }
  }

  @Override
  public boolean isTrackEncodable(AudioTrack track) {
    return true;
  }

  @Override
  public void encodeTrack(AudioTrack track, DataOutput output) throws IOException {
    // No special values to encode
  }

  @Override
  public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
    return new YandexMusicAudioTrack(trackInfo, this);
  }

  @Override
  public void shutdown() {
    ExceptionTools.closeWithWarnings(httpInterfaceManager);
    ExceptionTools.closeWithWarnings(httpApiInterfaceManager);
  }

  /**
   * @return Get an HTTP interface for a playing track.
   */
  public HttpInterface getHttpInterface() {
    return httpInterfaceManager.getInterface();
  }

  @Override
  public void configureRequests(Function<RequestConfig, RequestConfig> configurator) {
    httpInterfaceManager.configureRequests(configurator);
  }

  @Override
  public void configureBuilder(Consumer<HttpClientBuilder> configurator) {
    httpInterfaceManager.configureBuilder(configurator);
  }

  public void configureApiRequests(Function<RequestConfig, RequestConfig> configurator) {
    httpApiInterfaceManager.configureRequests(configurator);
  }

  public void configureApiBuilder(Consumer<HttpClientBuilder> configurator) {
    httpApiInterfaceManager.configureBuilder(configurator);
  }

  @Override
  public String getSourceName() {
    return "yandex-music";
  }

  private interface ApiExtractor<T> {
    T extract(HttpInterface httpInterface, JsonBrowser result) throws Exception;
  }

  private class DownloadInfo {
    String host;
    String path;
    String ts;
    String region;
    String s;
  }
}
