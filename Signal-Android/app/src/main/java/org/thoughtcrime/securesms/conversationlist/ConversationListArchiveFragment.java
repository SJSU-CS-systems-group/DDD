/*
 * Copyright (C) 2015 Open Whisper Systems
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
package org.thoughtcrime.securesms.conversationlist;

import android.annotation.SuppressLint;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.WorkerThread;
import androidx.appcompat.view.ActionMode;
import androidx.appcompat.widget.Toolbar;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.components.registration.PulsingFloatingActionButton;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.util.ConversationUtil;
import org.thoughtcrime.securesms.util.task.SnackbarAsyncTask;
import org.thoughtcrime.securesms.util.views.Stub;

import java.util.Set;


public class ConversationListArchiveFragment extends ConversationListFragment implements ActionMode.Callback
{
  private View                        coordinator;
  private RecyclerView                list;
  private Stub<View>                  emptyState;
  private PulsingFloatingActionButton fab;
  private PulsingFloatingActionButton cameraFab;
  private Stub<Toolbar>               toolbar;

  public static ConversationListArchiveFragment newInstance() {
    return new ConversationListArchiveFragment();
  }

  @Override
  public void onCreate(Bundle icicle) {
    super.onCreate(icicle);
    setHasOptionsMenu(false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    toolbar = requireCallback().getBasicToolbar();

    super.onViewCreated(view, savedInstanceState);

    coordinator = view.findViewById(R.id.coordinator);
    list        = view.findViewById(R.id.list);
    emptyState  = new Stub<>(view.findViewById(R.id.empty_state));
    fab         = view.findViewById(R.id.fab);
    cameraFab   = view.findViewById(R.id.camera_fab);

    toolbar.get().setNavigationOnClickListener(v -> NavHostFragment.findNavController(this).popBackStack());
    toolbar.get().setTitle(R.string.AndroidManifest_archived_conversations);

    fab.hide();
    cameraFab.hide();
  }

  @Override
  protected void onPostSubmitList(int conversationCount) {
    list.setVisibility(View.VISIBLE);

    if (emptyState.resolved()) {
      emptyState.get().setVisibility(View.GONE);
    }
  }

  @Override
  protected boolean isArchived() {
    return true;
  }

  @Override
  protected @NonNull Toolbar getToolbar(@NonNull View rootView) {
    return toolbar.get();
  }

  @Override
  protected @StringRes int getArchivedSnackbarTitleRes() {
    return R.plurals.ConversationListFragment_moved_conversations_to_inbox;
  }

  @Override
  protected @DrawableRes int getArchiveIconRes() {
    return R.drawable.symbol_archive_android_up_24;
  }

  @Override
  @WorkerThread
  protected void archiveThreads(Set<Long> threadIds) {
    SignalDatabase.threads().setArchived(threadIds, false);
  }

  @Override
  @WorkerThread
  protected void reverseArchiveThreads(Set<Long> threadIds) {
    SignalDatabase.threads().setArchived(threadIds, true);
  }

  @SuppressLint("StaticFieldLeak")
  @Override
  protected void onItemSwiped(long threadId, int unreadCount, int unreadSelfMentionsCount) {
    archiveDecoration.onArchiveStarted();
    itemAnimator.enable();

    new SnackbarAsyncTask<Long>(getViewLifecycleOwner().getLifecycle(),
                                coordinator,
                                getResources().getQuantityString(R.plurals.ConversationListFragment_moved_conversations_to_inbox, 1, 1),
                                getString(R.string.ConversationListFragment_undo),
                                getResources().getColor(R.color.amber_500),
                                Snackbar.LENGTH_LONG,
                                false)
    {
      @Override
      protected void executeAction(@Nullable Long parameter) {
        SignalDatabase.threads().unarchiveConversation(threadId);
        ConversationUtil.refreshRecipientShortcuts();
      }

      @Override
      protected void reverseAction(@Nullable Long parameter) {
        SignalDatabase.threads().archiveConversation(threadId);
        ConversationUtil.refreshRecipientShortcuts();
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, threadId);
  }

  @Override
  void updateEmptyState(boolean isConversationEmpty) {
    // Do nothing
  }
}


