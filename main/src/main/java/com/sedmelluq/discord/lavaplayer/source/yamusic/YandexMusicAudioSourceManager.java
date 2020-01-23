package com.sedmelluq.discord.lavaplayer.source.yamusic;

import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.ExceptionTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClientBuilder;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Audio source manager that implements finding Yandex Music tracks based on URL.
 */
public class YandexMusicAudioSourceManager implements AudioSourceManager, HttpConfigurable {
  private static final String TRACK_URL_REGEX = "^https?://music\\.yandex\\.[a-zA-Z]+/album/([0-9]+)/track/([0-9]+)$";
  private static final String ALBUM_URL_REGEX = "^https?://music\\.yandex\\.[a-zA-Z]+/album/([0-9]+)$";
  private static final String PLAYLIST_URL_REGEX = "^https?://music\\.yandex\\.[a-zA-Z]+/users/(.+)/playlists/([0-9]+)$";


  private static final Pattern trackUrlPattern = Pattern.compile(TRACK_URL_REGEX);
  private static final Pattern albumUrlPattern = Pattern.compile(ALBUM_URL_REGEX);
  private static final Pattern playlistUrlPattern = Pattern.compile(PLAYLIST_URL_REGEX);

  private final boolean allowSearch;

  private final HttpInterfaceManager httpInterfaceManager;
  private final YandexMusicDirectUrlLoader directUrlLoader;
  private final YandexMusicTrackLoader trackLoader;
  private final YandexMusicPlaylistLoader playlistLoader;
  private final YandexMusicSearchResultLoader searchResultLoader;

  public YandexMusicAudioSourceManager() {
    this(true);
  }

  public YandexMusicAudioSourceManager(boolean allowSearch) {
    this(
        allowSearch,
        new DefaultYandexMusicTrackLoader(),
        new DefaultYandexMusicPlaylistLoader(),
        new DefaultYandexMusicDirectUrlLoader(),
        new DefaultYandexSearchProvider()
    );
  }

  /**
   * Create an instance.
   */
  public YandexMusicAudioSourceManager(
      boolean allowSearch,
      YandexMusicTrackLoader trackLoader,
      YandexMusicPlaylistLoader playlistLoader,
      YandexMusicDirectUrlLoader directUrlLoader,
      YandexMusicSearchResultLoader searchResultLoader) {

    httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager();
    httpInterfaceManager.setHttpContextFilter(new YandexHttpContextFilter());
    this.allowSearch = allowSearch;
    this.trackLoader = trackLoader;
    this.playlistLoader = playlistLoader;
    this.directUrlLoader = directUrlLoader;
    this.searchResultLoader = searchResultLoader;
  }

  @Override
  public AudioItem loadItem(DefaultAudioPlayerManager manager, AudioReference reference) {
    Matcher matcher;
    if ((matcher = trackUrlPattern.matcher(reference.identifier)).matches()) {
      return trackLoader.loadTrack(matcher.group(1), matcher.group(2), this::getTrack);
    }
    if ((matcher = playlistUrlPattern.matcher(reference.identifier)).matches()) {
      return playlistLoader.loadPlaylist(matcher.group(1), matcher.group(2), "tracks", this::getTrack);
    }
    if ((matcher = albumUrlPattern.matcher(reference.identifier)).matches()) {
      return playlistLoader.loadPlaylist(matcher.group(1), "volumes", this::getTrack);
    }
    if (allowSearch) {
      return searchResultLoader.loadSearchResult(reference.identifier, playlistLoader, this::getTrack);
    }
    return null;
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

  public AudioTrack getTrack(AudioTrackInfo info) {
    return new YandexMusicAudioTrack(info, this);
  }

  @Override
  public void shutdown() {
    ExceptionTools.closeWithWarnings(httpInterfaceManager);
    trackLoader.shutdown();
    playlistLoader.shutdown();
    searchResultLoader.shutdown();
    directUrlLoader.shutdown();
  }

  public YandexMusicDirectUrlLoader getDirectUrlLoader() {
    return directUrlLoader;
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
    trackLoader.getHttpConfiguration().configureRequests(configurator);
    playlistLoader.getHttpConfiguration().configureRequests(configurator);
    searchResultLoader.getHttpConfiguration().configureRequests(configurator);
    directUrlLoader.getHttpConfiguration().configureRequests(configurator);
  }

  public void configureApiBuilder(Consumer<HttpClientBuilder> configurator) {
    trackLoader.getHttpConfiguration().configureBuilder(configurator);
    playlistLoader.getHttpConfiguration().configureBuilder(configurator);
    searchResultLoader.getHttpConfiguration().configureBuilder(configurator);
    directUrlLoader.getHttpConfiguration().configureBuilder(configurator);
  }

  @Override
  public String getSourceName() {
    return "yandex-music";
  }

}
