package com.sedmelluq.discord.lavaplayer.source.yamusic;

import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.track.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.SUSPICIOUS;

public class DefaultYandexMusicPlaylistLoader extends DefaultYandexMusicTrackLoader implements YandexMusicPlaylistLoader {
  private static final String PLAYLIST_INFO_FORMAT = "https://api.music.yandex.net/users/%s/playlists/%s";
  private static final String ALBUM_INFO_FORMAT = "https://api.music.yandex.net/albums/%s/with-tracks";

  @Override
  public AudioItem loadPlaylist(String login, String id, String trackProperty, Function<AudioTrackInfo, AudioTrack> trackFactory) {
    return loadPlaylistUrl(String.format(PLAYLIST_INFO_FORMAT, login, id), trackProperty, trackFactory);
  }

  @Override
  public AudioItem loadPlaylist(String album, String trackProperty, Function<AudioTrackInfo, AudioTrack> trackFactory) {
    return loadPlaylistUrl(String.format(ALBUM_INFO_FORMAT, album), trackProperty, trackFactory);
  }

  private AudioItem loadPlaylistUrl(String url, String trackProperty, Function<AudioTrackInfo, AudioTrack> trackFactory) {
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
            tracks.add(YandexMusicUtils.extractTrack(innerInfo, trackFactory));
          }
        } else {
          tracks.add(YandexMusicUtils.extractTrack(trackInfo, trackFactory));
        }
      }
      return new BasicAudioPlaylist(result.get("title").text(), tracks, null, false);
    });
  }
}