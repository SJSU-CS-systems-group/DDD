package org.thoughtcrime.securesms.phonenumbers;


import org.junit.Before;
import org.junit.Test;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.testutil.EmptyLogger;

import static junit.framework.Assert.assertEquals;

public class PhoneNumberFormatterTest {

  @Before
  public void setup() {
    Log.initialize(new EmptyLogger());
  }

  @Test
  public void testAddressString() throws Exception {
    PhoneNumberFormatter formatter = new PhoneNumberFormatter("+14152222222");
    assertEquals(formatter.format("bonbon"), "bonbon");
  }

  @Test
  public void testAddressShortCode() throws Exception {
    PhoneNumberFormatter formatter = new PhoneNumberFormatter("+14152222222");
    assertEquals(formatter.format("40404"), "40404");
  }

  @Test
  public void testEmailAddress() throws Exception {
    PhoneNumberFormatter formatter = new PhoneNumberFormatter("+14152222222");
    assertEquals(formatter.format("junk@junk.net"), "junk@junk.net");
  }

  @Test
  public void testNumberArbitrary() throws Exception {
    PhoneNumberFormatter formatter = new PhoneNumberFormatter("+14152222222");
    assertEquals(formatter.format("(415) 111-1122"), "+14151111122");
    assertEquals(formatter.format("(415) 111 1123"), "+14151111123");
    assertEquals(formatter.format("415-111-1124"), "+14151111124");
    assertEquals(formatter.format("415.111.1125"), "+14151111125");
    assertEquals(formatter.format("+1 415.111.1126"), "+14151111126");
    assertEquals(formatter.format("+1 415 111 1127"), "+14151111127");
    assertEquals(formatter.format("+1 (415) 111 1128"), "+14151111128");
    assertEquals(formatter.format("911"), "911");
    assertEquals(formatter.format("+456-7890"), "+4567890");

    formatter = new PhoneNumberFormatter("+442079460010");
    assertEquals(formatter.format("(020) 7946 0018"), "+442079460018");
  }

  @Test
  public void testUsNumbers() {
    PhoneNumberFormatter formatter = new PhoneNumberFormatter("+16105880522");

    assertEquals("+551234567890", formatter.format("+551234567890"));
    assertEquals("+11234567890", formatter.format("(123) 456-7890"));
    assertEquals("+11234567890", formatter.format("1234567890"));
    assertEquals("+16104567890", formatter.format("456-7890"));
    assertEquals("+16104567890", formatter.format("4567890"));
    assertEquals("+11234567890", formatter.format("011 1 123 456 7890"));
    assertEquals("+5511912345678", formatter.format("0115511912345678"));
    assertEquals("+16105880522", formatter.format("+16105880522"));
  }

  @Test
  public void testBrNumbers() {
    PhoneNumberFormatter formatter = new PhoneNumberFormatter("+5521912345678");

    assertEquals("+16105880522", formatter.format("+16105880522"));
    assertEquals("+552187654321", formatter.format("8765 4321"));
    assertEquals("+5521987654321", formatter.format("9 8765 4321"));
    assertEquals("+552287654321", formatter.format("22 8765 4321"));
    assertEquals("+5522987654321", formatter.format("22 9 8765 4321"));
    assertEquals("+551234567890", formatter.format("+55 (123) 456-7890"));
    assertEquals("+14085048577", formatter.format("002214085048577"));
    assertEquals("+5511912345678", formatter.format("011912345678"));
    assertEquals("+5511912345678", formatter.format("02111912345678"));
    assertEquals("+551234567", formatter.format("1234567"));
    assertEquals("+5521912345678", formatter.format("+5521912345678"));
    assertEquals("+552112345678", formatter.format("+552112345678"));
  }

  @Test
  public void testGroup() throws Exception {
    PhoneNumberFormatter formatter = new PhoneNumberFormatter("+14152222222");
    assertEquals(formatter.format("__textsecure_group__!foobar"), "__textsecure_group__!foobar");
  }

  @Test
  public void testLostLocalNumber() throws Exception {
    PhoneNumberFormatter formatter = new PhoneNumberFormatter("US", true);
    assertEquals(formatter.format("(415) 111-1122"), "+14151111122");
  }

  @Test
  public void testParseNumberFailWithoutLocalNumber() {
    PhoneNumberFormatter formatter = new PhoneNumberFormatter("US", true);
    assertEquals("+144444444441234512312312312312312312312", formatter.format("44444444441234512312312312312312312312"));
    assertEquals("+144444444441234512312312312312312312312", formatter.format("144444444441234512312312312312312312312"));
  }
}
