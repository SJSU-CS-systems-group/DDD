package org.thoughtcrime.securesms.util;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

import static junit.framework.TestCase.assertEquals;

@RunWith(Parameterized.class)
public class LinkUtilTest_isLegal {

  private final String  input;
  private final boolean output;

  @Parameterized.Parameters
  public static Collection<Object[]> data() {
    return Arrays.asList(new Object[][]{
        { "google.com",                            true  },
        { "foo.google.com",                        true  },
        { "https://foo.google.com",                true  },
        { "https://foo.google.com/some/path.html", true  },
        { "кц.рф",                                 true  },
        { "https://кц.рф/some/path",               true  },
        { "https://abcdefg.onion",                 true  },
        { "https://abcdefg.i2p",                   true  },
        { "http://кц.com",                         false },
        { "кц.com",                                false },
        { "http://asĸ.com",                        false },
        { "http://foo.кц.рф",                      false },
        { "кц.рф\u202C",                           false },
        { "кц.рф\u202D",                           false },
        { "кц.рф\u202E",                           false },
        { "кц.рф\u2500",                           false },
        { "кц.рф\u25AA",                           false },
        { "кц.рф\u25FF",                           false },
        { "",                                      false }
    });
  }

  public LinkUtilTest_isLegal(String input, boolean output) {
    this.input  = input;
    this.output = output;
  }

  @Test
  public void isLegal() {
    assertEquals(output, LinkUtil.isLegalUrl(input));
  }
}
