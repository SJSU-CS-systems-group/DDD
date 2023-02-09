package org.thoughtcrime.securesms;

import android.app.Activity;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity;
import org.thoughtcrime.securesms.conversation.ConversationIntents;
import org.thoughtcrime.securesms.groups.ui.creategroup.CreateGroupActivity;
import org.thoughtcrime.securesms.insights.InsightsLauncher;
import org.thoughtcrime.securesms.recipients.RecipientId;

public class MainNavigator {

  public static final int REQUEST_CONFIG_CHANGES = 901;

  private final MainActivity activity;

  public MainNavigator(@NonNull MainActivity activity) {
    this.activity = activity;
  }

  public static MainNavigator get(@NonNull Activity activity) {
    if (!(activity instanceof MainActivity)) {
      throw new IllegalArgumentException("Activity must be an instance of MainActivity!");
    }

    return ((MainActivity) activity).getNavigator();
  }

  /**
   * @return True if the back pressed was handled in our own custom way, false if it should be given
   *         to the system to do the default behavior.
   */
  public boolean onBackPressed() {
    Fragment fragment = getFragmentManager().findFragmentById(R.id.fragment_container);

    if (fragment instanceof BackHandler) {
      return ((BackHandler) fragment).onBackPressed();
    }

    return false;
  }

  public void goToConversation(@NonNull RecipientId recipientId, long threadId, int distributionType, int startingPosition) {
    Intent intent = ConversationIntents.createBuilder(activity, recipientId, threadId)
                                       .withDistributionType(distributionType)
                                       .withStartingPosition(startingPosition)
                                       .build();

    activity.startActivity(intent);
    activity.overridePendingTransition(R.anim.slide_from_end, R.anim.fade_scale_out);
  }

  public void goToAppSettings() {
    activity.startActivityForResult(AppSettingsActivity.home(activity), REQUEST_CONFIG_CHANGES);
  }

  public void goToGroupCreation() {
    activity.startActivity(CreateGroupActivity.newIntent(activity));
  }

  public void goToInvite() {
    Intent intent = new Intent(activity, InviteActivity.class);
    activity.startActivity(intent);
  }

  public void goToInsights() {
    InsightsLauncher.showInsightsDashboard(activity.getSupportFragmentManager());
  }

  private @NonNull FragmentManager getFragmentManager() {
    return activity.getSupportFragmentManager();
  }

  public interface BackHandler {
    /**
     * @return True if the back pressed was handled in our own custom way, false if it should be given
     *         to the system to do the default behavior.
     */
    boolean onBackPressed();
  }
}
