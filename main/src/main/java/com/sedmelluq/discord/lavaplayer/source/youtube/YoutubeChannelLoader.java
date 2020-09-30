package com.sedmelluq.discord.lavaplayer.source.youtube;

import com.sedmelluq.discord.lavaplayer.tools.http.ExtendedHttpConfigurable;

import java.util.List;

public interface YoutubeChannelLoader {
  List<YoutubeChannel> loadSearchResult(String query);

  ExtendedHttpConfigurable getHttpConfiguration();
}
