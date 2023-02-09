package org.thoughtcrime.securesms.conversationlist

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.lifecycle.LifecycleOwner
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.contacts.paged.ArbitraryRepository
import org.thoughtcrime.securesms.contacts.paged.ContactSearchAdapter
import org.thoughtcrime.securesms.contacts.paged.ContactSearchConfiguration
import org.thoughtcrime.securesms.contacts.paged.ContactSearchData
import org.thoughtcrime.securesms.contacts.paged.ContactSearchKey
import org.thoughtcrime.securesms.conversationlist.model.ConversationSet
import org.thoughtcrime.securesms.mms.GlideRequests
import org.thoughtcrime.securesms.util.adapter.mapping.LayoutFactory
import org.thoughtcrime.securesms.util.adapter.mapping.MappingModel
import org.thoughtcrime.securesms.util.adapter.mapping.MappingViewHolder
import org.thoughtcrime.securesms.util.visible
import java.util.Locale

/**
 * Adapter for ConversationList search. Adds factories to render ThreadModel and MessageModel using ConversationListItem,
 * as well as ChatFilter row support and empty state handler.
 */
class ConversationListSearchAdapter(
  context: Context,
  fixedContacts: Set<ContactSearchKey>,
  displayCheckBox: Boolean,
  displaySmsTag: DisplaySmsTag,
  displayPhoneNumber: DisplayPhoneNumber,
  onClickedCallbacks: ConversationListSearchClickCallbacks,
  longClickCallbacks: LongClickCallbacks,
  storyContextMenuCallbacks: StoryContextMenuCallbacks,
  lifecycleOwner: LifecycleOwner,
  glideRequests: GlideRequests
) : ContactSearchAdapter(context, fixedContacts, displayCheckBox, displaySmsTag, displayPhoneNumber, onClickedCallbacks, longClickCallbacks, storyContextMenuCallbacks) {

  init {
    registerFactory(
      ThreadModel::class.java,
      LayoutFactory({ ThreadViewHolder(onClickedCallbacks::onThreadClicked, lifecycleOwner, glideRequests, it) }, R.layout.conversation_list_item_view)
    )
    registerFactory(
      MessageModel::class.java,
      LayoutFactory({ MessageViewHolder(onClickedCallbacks::onMessageClicked, lifecycleOwner, glideRequests, it) }, R.layout.conversation_list_item_view)
    )
    registerFactory(
      ChatFilterMappingModel::class.java,
      LayoutFactory({ ChatFilterViewHolder(it, onClickedCallbacks::onClearFilterClicked) }, R.layout.conversation_list_item_clear_filter)
    )
    registerFactory(
      ChatFilterEmptyMappingModel::class.java,
      LayoutFactory({ ChatFilterViewHolder(it, onClickedCallbacks::onClearFilterClicked) }, R.layout.conversation_list_item_clear_filter_empty)
    )
    registerFactory(
      EmptyModel::class.java,
      LayoutFactory({ EmptyViewHolder(it) }, R.layout.conversation_list_empty_search_state)
    )
    registerFactory(
      GroupWithMembersModel::class.java,
      LayoutFactory({ GroupWithMembersViewHolder(onClickedCallbacks::onGroupWithMembersClicked, lifecycleOwner, glideRequests, it) }, R.layout.conversation_list_item_view)
    )
  }

  private class EmptyViewHolder(
    itemView: View
  ) : MappingViewHolder<EmptyModel>(itemView) {

    private val noResults = itemView.findViewById<TextView>(R.id.search_no_results)

    override fun bind(model: EmptyModel) {
      println("BIND")
      noResults.text = context.getString(R.string.SearchFragment_no_results, model.empty.query ?: "")
    }
  }

  private class ThreadViewHolder(
    private val threadListener: OnClickedCallback<ContactSearchData.Thread>,
    private val lifecycleOwner: LifecycleOwner,
    private val glideRequests: GlideRequests,
    itemView: View
  ) : MappingViewHolder<ThreadModel>(itemView) {
    override fun bind(model: ThreadModel) {
      itemView.setOnClickListener {
        threadListener.onClicked(itemView, model.thread, false)
      }

      (itemView as ConversationListItem).bindThread(
        lifecycleOwner,
        model.thread.threadRecord,
        glideRequests,
        Locale.getDefault(),
        emptySet(),
        ConversationSet(),
        model.thread.query
      )
    }
  }

  private class MessageViewHolder(
    private val messageListener: OnClickedCallback<ContactSearchData.Message>,
    private val lifecycleOwner: LifecycleOwner,
    private val glideRequests: GlideRequests,
    itemView: View
  ) : MappingViewHolder<MessageModel>(itemView) {
    override fun bind(model: MessageModel) {
      itemView.setOnClickListener {
        messageListener.onClicked(itemView, model.message, false)
      }

      (itemView as ConversationListItem).bindMessage(
        lifecycleOwner,
        model.message.messageResult,
        glideRequests,
        Locale.getDefault(),
        model.message.query
      )
    }
  }

  private class GroupWithMembersViewHolder(
    private val groupWithMembersListener: OnClickedCallback<ContactSearchData.GroupWithMembers>,
    private val lifecycleOwner: LifecycleOwner,
    private val glideRequests: GlideRequests,
    itemView: View
  ) : MappingViewHolder<GroupWithMembersModel>(itemView) {
    override fun bind(model: GroupWithMembersModel) {
      itemView.setOnClickListener {
        groupWithMembersListener.onClicked(itemView, model.groupWithMembers, false)
      }

      (itemView as ConversationListItem).bindGroupWithMembers(
        lifecycleOwner,
        model.groupWithMembers,
        glideRequests,
        Locale.getDefault()
      )
    }
  }

  private open class BaseChatFilterMappingModel<T : BaseChatFilterMappingModel<T>>(val options: ChatFilterOptions) : MappingModel<T> {
    override fun areItemsTheSame(newItem: T): Boolean = true

    override fun areContentsTheSame(newItem: T): Boolean = options == newItem.options
  }

  private class ChatFilterMappingModel(options: ChatFilterOptions) : BaseChatFilterMappingModel<ChatFilterMappingModel>(options)

  private class ChatFilterEmptyMappingModel(options: ChatFilterOptions) : BaseChatFilterMappingModel<ChatFilterEmptyMappingModel>(options)

  private class ChatFilterViewHolder<T : BaseChatFilterMappingModel<T>>(itemView: View, listener: () -> Unit) : MappingViewHolder<T>(itemView) {

    private val tip = itemView.findViewById<View>(R.id.clear_filter_tip)

    init {
      itemView.findViewById<View>(R.id.clear_filter).setOnClickListener { listener() }
    }

    override fun bind(model: T) {
      tip.visible = model.options == ChatFilterOptions.WITH_TIP
    }
  }

  enum class ChatFilterOptions(val code: String) {
    WITH_TIP("with-tip"),
    WITHOUT_TIP("without-tip");

    companion object {
      fun fromCode(code: String): ChatFilterOptions {
        return values().firstOrNull { it.code == code } ?: WITHOUT_TIP
      }
    }
  }

  class ChatFilterRepository : ArbitraryRepository {
    override fun getSize(section: ContactSearchConfiguration.Section.Arbitrary, query: String?): Int = section.types.size

    override fun getData(
      section: ContactSearchConfiguration.Section.Arbitrary,
      query: String?,
      startIndex: Int,
      endIndex: Int,
      totalSearchSize: Int
    ): List<ContactSearchData.Arbitrary> {
      return section.types.map {
        ContactSearchData.Arbitrary(it, bundleOf("total-size" to totalSearchSize))
      }
    }

    override fun getMappingModel(arbitrary: ContactSearchData.Arbitrary): MappingModel<*> {
      val options = ChatFilterOptions.fromCode(arbitrary.type)
      val totalSearchSize = arbitrary.data?.getInt("total-size", -1) ?: -1
      return if (totalSearchSize == 1) {
        ChatFilterEmptyMappingModel(options)
      } else {
        ChatFilterMappingModel(options)
      }
    }
  }

  interface ConversationListSearchClickCallbacks : ClickCallbacks {
    fun onThreadClicked(view: View, thread: ContactSearchData.Thread, isSelected: Boolean)
    fun onMessageClicked(view: View, thread: ContactSearchData.Message, isSelected: Boolean)
    fun onGroupWithMembersClicked(view: View, groupWithMembers: ContactSearchData.GroupWithMembers, isSelected: Boolean)
    fun onClearFilterClicked()
  }
}
