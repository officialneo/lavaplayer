package com.sedmelluq.discord.lavaplayer.source.youtube;

import java.io.Serializable;

public class YoutubeChannel implements Serializable {

  private static final long serialVersionUID = -88060328513982218L;

  private final String id;

  private final String title;

  private final String description;

  private final String thumbnailUrl;

  public YoutubeChannel(String id, String title, String description, String thumbnailUrl) {
    this.id = id;
    this.title = title;
    this.description = description;
    this.thumbnailUrl = thumbnailUrl;
  }

  public String getId() {
    return id;
  }

  public String getTitle() {
    return title;
  }

  public String getDescription() {
    return description;
  }

  public String getThumbnailUrl() {
    return thumbnailUrl;
  }
}
