package org.signal.core.util.money;

import androidx.annotation.NonNull;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;

public class FiatMoney {
  private final BigDecimal amount;
  private final Currency   currency;
  private final long       timestamp;

  public FiatMoney(@NonNull BigDecimal amount, @NonNull Currency currency) {
    this(amount, currency, 0);
  }

  public FiatMoney(@NonNull BigDecimal amount, @NonNull Currency currency, long timestamp) {
    this.amount    = amount;
    this.currency  = currency;
    this.timestamp = timestamp;
  }

  public @NonNull BigDecimal getAmount() {
    return amount;
  }

  public @NonNull Currency getCurrency() {
    return currency;
  }

  public long getTimestamp() {
    return timestamp;
  }

  /**
   * @return amount, rounded to the default fractional amount.
   */
  public @NonNull String getDefaultPrecisionString() {
    return getDefaultPrecisionString(Locale.getDefault());
  }

  /**
   * @return amount, rounded to the default fractional amount.
   */
  public @NonNull String getDefaultPrecisionString(@NonNull Locale locale) {
    NumberFormat formatter = NumberFormat.getInstance(locale);
    formatter.setMinimumFractionDigits(currency.getDefaultFractionDigits());
    formatter.setGroupingUsed(false);

    return formatter.format(amount);
  }

  /**
   * Note: This special cases UGX to act as two decimal.
   *
   * @return amount, in smallest possible units (cents, yen, etc.)
   */
  public @NonNull String getMinimumUnitPrecisionString() {
    NumberFormat formatter = NumberFormat.getInstance(Locale.US);
    formatter.setMaximumFractionDigits(0);
    formatter.setGroupingUsed(false);

    BigDecimal multiplicand = BigDecimal.TEN.pow(currency.getCurrencyCode().equals("UGX") ? 2 : currency.getDefaultFractionDigits());

    return formatter.format(amount.multiply(multiplicand));
  }

  public static boolean equals(FiatMoney left, FiatMoney right) {
    return Objects.equals(left.amount, right.amount) &&
           Objects.equals(left.currency, right.currency) &&
           Objects.equals(left.timestamp, right.timestamp);
  }

  @Override
  public String toString() {
    return "FiatMoney{" +
           "amount=" + amount +
           ", currency=" + currency +
           ", timestamp=" + timestamp +
           '}';
  }
}
