package com.sedmelluq.discord.lavaplayer.container.adts;

import com.sedmelluq.discord.lavaplayer.container.MediaContainerDetection;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerDetectionResult;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerHints;
import com.sedmelluq.discord.lavaplayer.container.AbstractMediaContainerProbe;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static com.sedmelluq.discord.lavaplayer.tools.DataFormatTools.defaultOnNull;

/**
 * Container detection probe for ADTS stream format.
 */
public class AdtsContainerProbe extends AbstractMediaContainerProbe {
  private static final Logger log = LoggerFactory.getLogger(AdtsContainerProbe.class);

  @Override
  public String getName() {
    return "adts";
  }

  @Override
  public boolean matchesHints(MediaContainerHints hints) {
    return false;
  }

  @Override
  public MediaContainerDetectionResult probe(AudioReference reference, SeekableInputStream inputStream) throws IOException {
    AdtsStreamReader reader = new AdtsStreamReader(inputStream);

    if (reader.findPacketHeader(MediaContainerDetection.STREAM_SCAN_DISTANCE) == null) {
      return null;
    }

    log.debug("Track {} is an ADTS stream.", reference.identifier);

    return new MediaContainerDetectionResult(this, new AudioTrackInfo(
        reference.title != null ? reference.title : getDefaultTitle(inputStream),
        getDefaultArtist(inputStream),
        Long.MAX_VALUE,
        reference.identifier,
        true,
        defaultOnNull(getDefaultUrl(inputStream), reference.identifier)
    ));
  }

  @Override
  public AudioTrack createTrack(AudioTrackInfo trackInfo, SeekableInputStream inputStream) {
    return new AdtsAudioTrack(trackInfo, inputStream);
  }
}
