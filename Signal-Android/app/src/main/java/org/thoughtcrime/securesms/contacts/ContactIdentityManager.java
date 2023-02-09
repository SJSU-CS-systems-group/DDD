package org.thoughtcrime.securesms.contacts;

import android.content.Context;
import android.net.Uri;

import java.util.List;

public abstract class ContactIdentityManager {

  public static ContactIdentityManager getInstance(Context context) {
    return new ContactIdentityManagerICS(context);
  }

  protected final Context context;

  public ContactIdentityManager(Context context) {
    this.context = context.getApplicationContext();
  }

  public abstract Uri        getSelfIdentityUri();
  public abstract boolean    isSelfIdentityAutoDetected();
  public abstract List<Long> getSelfIdentityRawContactIds();

}
