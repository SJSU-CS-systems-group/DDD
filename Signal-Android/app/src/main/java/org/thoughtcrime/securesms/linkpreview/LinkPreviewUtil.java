package org.thoughtcrime.securesms.linkpreview;

import android.annotation.SuppressLint;
import android.text.SpannableString;
import android.text.style.URLSpan;
import android.text.util.Linkify;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.text.HtmlCompat;
import androidx.core.text.util.LinkifyCompat;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;

import org.thoughtcrime.securesms.util.DateUtils;
import org.thoughtcrime.securesms.util.LinkUtil;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.signalservice.api.util.OptionalUtil;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;

public final class LinkPreviewUtil {

  private static final Pattern OPEN_GRAPH_TAG_PATTERN     = Pattern.compile("<\\s*meta[^>]*property\\s*=\\s*\"\\s*og:([^\"]+)\"[^>]*/?\\s*>");
  private static final Pattern ARTICLE_TAG_PATTERN        = Pattern.compile("<\\s*meta[^>]*property\\s*=\\s*\"\\s*article:([^\"]+)\"[^>]*/?\\s*>");
  private static final Pattern OPEN_GRAPH_CONTENT_PATTERN = Pattern.compile("content\\s*=\\s*\"([^\"]*)\"");
  private static final Pattern TITLE_PATTERN              = Pattern.compile("<\\s*title[^>]*>(.*)<\\s*/title[^>]*>");
  private static final Pattern FAVICON_PATTERN            = Pattern.compile("<\\s*link[^>]*rel\\s*=\\s*\".*icon.*\"[^>]*>");
  private static final Pattern FAVICON_HREF_PATTERN       = Pattern.compile("href\\s*=\\s*\"([^\"]*)\"");

  public static @Nullable String getTopLevelDomain(@Nullable String urlString) {
    if (!Util.isEmpty(urlString)) {
      HttpUrl url = HttpUrl.parse(urlString);
      if (url != null) {
        return url.topPrivateDomain();
      }
    }

    return null;
  }

  /**
   * @return All URLs allowed as previews in the source text.
   */
  public static @NonNull Links findValidPreviewUrls(@NonNull String text) {
    SpannableString spannable = new SpannableString(text);
    boolean         found     = LinkifyCompat.addLinks(spannable, Linkify.WEB_URLS);

    if (!found) {
      return Links.EMPTY;
    }

    return new Links(Stream.of(spannable.getSpans(0, spannable.length(), URLSpan.class))
                           .map(span -> new Link(span.getURL(), spannable.getSpanStart(span)))
                           .filter(link -> LinkUtil.isValidPreviewUrl(link.getUrl()))
                           .toList());
  }

  public static @NonNull OpenGraph parseOpenGraphFields(@Nullable String html) {
    if (html == null) {
      return new OpenGraph(Collections.emptyMap(), null, null);
    }

    Map<String, String> openGraphTags    = new HashMap<>();
    Matcher             openGraphMatcher = OPEN_GRAPH_TAG_PATTERN.matcher(html);

    while (openGraphMatcher.find()) {
      String tag      = openGraphMatcher.group();
      String property = openGraphMatcher.groupCount() > 0 ? openGraphMatcher.group(1) : null;

      if (property != null) {
        Matcher contentMatcher = OPEN_GRAPH_CONTENT_PATTERN.matcher(tag);
        if (contentMatcher.find() && contentMatcher.groupCount() > 0) {
          String content = fromDoubleEncoded(contentMatcher.group(1));
          openGraphTags.put(property.toLowerCase(), content);
        }
      }
    }

    Matcher articleMatcher = ARTICLE_TAG_PATTERN.matcher(html);

    while (articleMatcher.find()) {
      String tag      = articleMatcher.group();
      String property = articleMatcher.groupCount() > 0 ? articleMatcher.group(1) : null;

      if (property != null) {
        Matcher contentMatcher = OPEN_GRAPH_CONTENT_PATTERN.matcher(tag);
        if (contentMatcher.find() && contentMatcher.groupCount() > 0) {
          String content = fromDoubleEncoded(contentMatcher.group(1));
          openGraphTags.put(property.toLowerCase(), content);
        }
      }
    }

    String htmlTitle  = "";
    String faviconUrl = "";

    Matcher titleMatcher = TITLE_PATTERN.matcher(html);
    if (titleMatcher.find() && titleMatcher.groupCount() > 0) {
      htmlTitle = fromDoubleEncoded(titleMatcher.group(1));
    }

    Matcher faviconMatcher = FAVICON_PATTERN.matcher(html);
    if (faviconMatcher.find()) {
      Matcher faviconHrefMatcher = FAVICON_HREF_PATTERN.matcher(faviconMatcher.group());
      if (faviconHrefMatcher.find() && faviconHrefMatcher.groupCount() > 0) {
        faviconUrl = faviconHrefMatcher.group(1);
      }
    }

    return new OpenGraph(openGraphTags, htmlTitle, faviconUrl);
  }

  private static @NonNull String fromDoubleEncoded(@NonNull String html) {
    return HtmlCompat.fromHtml(HtmlCompat.fromHtml(html, 0).toString(), 0).toString();
  }

  public static final class OpenGraph {

    private final Map<String, String> values;

    private final @Nullable String htmlTitle;
    private final @Nullable String faviconUrl;

    private static final String KEY_TITLE            = "title";
    private static final String KEY_DESCRIPTION_URL  = "description";
    private static final String KEY_IMAGE_URL        = "image";
    private static final String KEY_PUBLISHED_TIME_1 = "published_time";
    private static final String KEY_PUBLISHED_TIME_2 = "article:published_time";
    private static final String KEY_MODIFIED_TIME_1  = "modified_time";
    private static final String KEY_MODIFIED_TIME_2  = "article:modified_time";

    public OpenGraph(@NonNull Map<String, String> values, @Nullable String htmlTitle, @Nullable String faviconUrl) {
      this.values     = values;
      this.htmlTitle  = htmlTitle;
      this.faviconUrl = faviconUrl;
    }

    public @NonNull Optional<String> getTitle() {
      return OptionalUtil.absentIfEmpty(Util.getFirstNonEmpty(values.get(KEY_TITLE), htmlTitle));
    }

    public @NonNull Optional<String> getImageUrl() {
      return OptionalUtil.absentIfEmpty(Util.getFirstNonEmpty(values.get(KEY_IMAGE_URL), faviconUrl));
    }

    @SuppressLint("ObsoleteSdkInt")
    public long getDate() {
      return Stream.of(values.get(KEY_PUBLISHED_TIME_1),
                       values.get(KEY_PUBLISHED_TIME_2),
                       values.get(KEY_MODIFIED_TIME_1),
                       values.get(KEY_MODIFIED_TIME_2))
                   .map(DateUtils::parseIso8601)
                   .filter(time -> time > 0)
                   .findFirst()
                   .orElse(0L);
    }

    public @NonNull Optional<String> getDescription() {
      return OptionalUtil.absentIfEmpty(values.get(KEY_DESCRIPTION_URL));
    }
  }

  public static class Links {
    static final Links EMPTY = new Links(Collections.emptyList());

    private final List<Link>  links;
    private final Set<String> urlSet;

    private Links(@NonNull List<Link> links) {
      this.links  = links;
      this.urlSet = Stream.of(links)
                          .map(link -> trimTrailingSlash(link.getUrl()))
                          .collect(Collectors.toSet());
    }

    public Optional<Link> findFirst() {
      return links.isEmpty() ? Optional.empty()
                             : Optional.of(links.get(0));
    }

    /**
     * Slightly forgiving comparison where it will ignore trailing '/' on the supplied url.
     */
    public boolean containsUrl(@NonNull String url) {
      return urlSet.contains(trimTrailingSlash(url));
    }

    private @NonNull String trimTrailingSlash(@NonNull String url) {
      return url.endsWith("/") ? url.substring(0, url.length() - 1)
                               : url;
    }

    public int size() {
      return links.size();
    }
  }
}
