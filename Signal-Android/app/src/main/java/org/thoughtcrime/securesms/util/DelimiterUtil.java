package org.thoughtcrime.securesms.util;


import java.util.regex.Pattern;

public class DelimiterUtil {

  public static String escape(String value, char delimiter) {
    return value.replace("" + delimiter, "\\" + delimiter);
  }

  public static String unescape(String value, char delimiter) {
    return value.replace("\\" + delimiter, "" + delimiter);
  }

  public static String[] split(String value, char delimiter) {
    if (value == null || value.length() == 0) {
      return new String[0];
    } else {
      String regex = "(?<!\\\\)" + Pattern.quote(delimiter + "");
      return value.split(regex);
    }
  }
}
