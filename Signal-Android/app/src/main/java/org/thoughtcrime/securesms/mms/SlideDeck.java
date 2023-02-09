/** 
 * Copyright (C) 2011 Whisper Systems
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.mms;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.annimon.stream.Stream;

import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.util.MediaUtil;
import org.thoughtcrime.securesms.util.Util;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

public class SlideDeck {

  private final List<Slide> slides = new LinkedList<>();

  public SlideDeck(@NonNull Context context, @NonNull List<? extends Attachment> attachments) {
    for (Attachment attachment : attachments) {
      Slide slide = MediaUtil.getSlideForAttachment(context, attachment);
      if (slide != null) slides.add(slide);
    }
  }

  public SlideDeck(@NonNull Context context, @NonNull Attachment attachment) {
    Slide slide = MediaUtil.getSlideForAttachment(context, attachment);
    if (slide != null) slides.add(slide);
  }

  public SlideDeck() {
  }

  public void clear() {
    slides.clear();
  }

  @NonNull
  public String getBody() {
    String body = "";

    for (Slide slide : slides) {
      Optional<String> slideBody = slide.getBody();

      if (slideBody.isPresent()) {
        body = slideBody.get();
      }
    }

    return body;
  }

  @NonNull
  public List<Attachment> asAttachments() {
    List<Attachment> attachments = new LinkedList<>();

    for (Slide slide : slides) {
      attachments.add(slide.asAttachment());
    }

    return attachments;
  }

  public void addSlide(Slide slide) {
    slides.add(slide);
  }

  public List<Slide> getSlides() {
    return slides;
  }

  public boolean containsMediaSlide() {
    for (Slide slide : slides) {
      if (slide.hasImage() || slide.hasVideo() || slide.hasAudio() || slide.hasDocument() || slide.hasSticker() || slide.hasViewOnce()) {
        return true;
      }
    }
    return false;
  }

  public @Nullable Slide getThumbnailSlide() {
    for (Slide slide : slides) {
      if (slide.hasImage()) {
        return slide;
      }
    }

    return null;
  }

  public @NonNull List<Slide> getThumbnailSlides() {
    return Stream.of(slides).filter(Slide::hasImage).toList();
  }

  public @Nullable AudioSlide getAudioSlide() {
    for (Slide slide : slides) {
      if (slide.hasAudio()) {
        return (AudioSlide)slide;
      }
    }

    return null;
  }

  public @Nullable DocumentSlide getDocumentSlide() {
    for (Slide slide: slides) {
      if (slide.hasDocument()) {
        return (DocumentSlide)slide;
      }
    }

    return null;
  }

  public @Nullable TextSlide getTextSlide() {
    for (Slide slide: slides) {
      if (MediaUtil.isLongTextType(slide.getContentType())) {
        return (TextSlide)slide;
      }
    }

    return null;
  }

  public @Nullable StickerSlide getStickerSlide() {
    for (Slide slide: slides) {
      if (slide.hasSticker()) {
        return (StickerSlide)slide;
      }
    }

    return null;
  }

  public @Nullable Slide getFirstSlide() {
    if (Util.hasItems(slides)) {
      return slides.get(0);
    }

    return null;
  }
}
