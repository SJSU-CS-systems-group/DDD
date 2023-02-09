package org.thoughtcrime.securesms.contacts.paged

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import org.signal.core.util.BreakIteratorCompat
import org.signal.core.util.dp
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.badges.BadgeImageView
import org.thoughtcrime.securesms.components.AvatarImageView
import org.thoughtcrime.securesms.components.FromTextView
import org.thoughtcrime.securesms.components.RecyclerViewFastScroller.FastScrollAdapter
import org.thoughtcrime.securesms.components.menu.ActionItem
import org.thoughtcrime.securesms.components.menu.SignalContextMenu
import org.thoughtcrime.securesms.contacts.LetterHeaderDecoration
import org.thoughtcrime.securesms.contacts.avatars.FallbackContactPhoto
import org.thoughtcrime.securesms.contacts.avatars.GeneratedContactPhoto
import org.thoughtcrime.securesms.database.model.DistributionListPrivacyMode
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.adapter.mapping.LayoutFactory
import org.thoughtcrime.securesms.util.adapter.mapping.MappingAdapter
import org.thoughtcrime.securesms.util.adapter.mapping.MappingModel
import org.thoughtcrime.securesms.util.adapter.mapping.MappingModelList
import org.thoughtcrime.securesms.util.adapter.mapping.MappingViewHolder
import org.thoughtcrime.securesms.util.adapter.mapping.PagingMappingAdapter
import org.thoughtcrime.securesms.util.visible

/**
 * Default contact search adapter, using the models defined in `ContactSearchItems`
 */
@Suppress("LeakingThis")
open class ContactSearchAdapter(
  private val context: Context,
  fixedContacts: Set<ContactSearchKey>,
  displayCheckBox: Boolean,
  displaySmsTag: DisplaySmsTag,
  displayPhoneNumber: DisplayPhoneNumber,
  onClickCallbacks: ClickCallbacks,
  longClickCallbacks: LongClickCallbacks,
  storyContextMenuCallbacks: StoryContextMenuCallbacks
) : PagingMappingAdapter<ContactSearchKey>(), FastScrollAdapter {

  init {
    registerStoryItems(this, displayCheckBox, onClickCallbacks::onStoryClicked, storyContextMenuCallbacks)
    registerKnownRecipientItems(this, fixedContacts, displayCheckBox, displaySmsTag, displayPhoneNumber, onClickCallbacks::onKnownRecipientClicked, longClickCallbacks::onKnownRecipientLongClick)
    registerHeaders(this)
    registerExpands(this, onClickCallbacks::onExpandClicked)
    registerFactory(UnknownRecipientModel::class.java, LayoutFactory({ UnknownRecipientViewHolder(it, onClickCallbacks::onUnknownRecipientClicked, displayCheckBox) }, R.layout.contact_search_unknown_item))
  }

  override fun getBubbleText(position: Int): CharSequence {
    val model = getItem(position)
    return if (model is FastScrollCharacterProvider) {
      model.getFastScrollCharacter(context)
    } else {
      " "
    }
  }

  interface FastScrollCharacterProvider {
    fun getFastScrollCharacter(context: Context): CharSequence
  }

  companion object {
    fun registerStoryItems(
      mappingAdapter: MappingAdapter,
      displayCheckBox: Boolean = false,
      storyListener: OnClickedCallback<ContactSearchData.Story>,
      storyContextMenuCallbacks: StoryContextMenuCallbacks? = null
    ) {
      mappingAdapter.registerFactory(
        StoryModel::class.java,
        LayoutFactory({ StoryViewHolder(it, displayCheckBox, storyListener, storyContextMenuCallbacks) }, R.layout.contact_search_item)
      )
    }

    fun registerKnownRecipientItems(
      mappingAdapter: MappingAdapter,
      fixedContacts: Set<ContactSearchKey>,
      displayCheckBox: Boolean,
      displaySmsTag: DisplaySmsTag,
      displayPhoneNumber: DisplayPhoneNumber,
      recipientListener: OnClickedCallback<ContactSearchData.KnownRecipient>,
      recipientLongClickCallback: OnLongClickedCallback<ContactSearchData.KnownRecipient>
    ) {
      mappingAdapter.registerFactory(
        RecipientModel::class.java,
        LayoutFactory({ KnownRecipientViewHolder(it, fixedContacts, displayCheckBox, displaySmsTag, displayPhoneNumber, recipientListener, recipientLongClickCallback) }, R.layout.contact_search_item)
      )
    }

    fun registerHeaders(mappingAdapter: MappingAdapter) {
      mappingAdapter.registerFactory(
        HeaderModel::class.java,
        LayoutFactory({ HeaderViewHolder(it) }, R.layout.contact_search_section_header)
      )
    }

    fun registerExpands(mappingAdapter: MappingAdapter, expandListener: (ContactSearchData.Expand) -> Unit) {
      mappingAdapter.registerFactory(
        ExpandModel::class.java,
        LayoutFactory({ ExpandViewHolder(it, expandListener) }, R.layout.contacts_expand_item)
      )
    }

    fun toMappingModelList(contactSearchData: List<ContactSearchData?>, selection: Set<ContactSearchKey>, arbitraryRepository: ArbitraryRepository?): MappingModelList {
      return MappingModelList(
        contactSearchData.filterNotNull().map {
          when (it) {
            is ContactSearchData.Story -> StoryModel(it, selection.contains(it.contactSearchKey), SignalStore.storyValues().userHasBeenNotifiedAboutStories)
            is ContactSearchData.KnownRecipient -> RecipientModel(it, selection.contains(it.contactSearchKey), it.shortSummary)
            is ContactSearchData.Expand -> ExpandModel(it)
            is ContactSearchData.Header -> HeaderModel(it)
            is ContactSearchData.TestRow -> error("This row exists for testing only.")
            is ContactSearchData.Arbitrary -> arbitraryRepository?.getMappingModel(it) ?: error("This row must be handled manually")
            is ContactSearchData.Message -> MessageModel(it)
            is ContactSearchData.Thread -> ThreadModel(it)
            is ContactSearchData.Empty -> EmptyModel(it)
            is ContactSearchData.GroupWithMembers -> GroupWithMembersModel(it)
            is ContactSearchData.UnknownRecipient -> UnknownRecipientModel(it)
          }
        }
      )
    }
  }

  /**
   * Story Model
   */
  class StoryModel(val story: ContactSearchData.Story, val isSelected: Boolean, val hasBeenNotified: Boolean) : MappingModel<StoryModel> {

    override fun areItemsTheSame(newItem: StoryModel): Boolean {
      return newItem.story == story
    }

    override fun areContentsTheSame(newItem: StoryModel): Boolean {
      return story.recipient.hasSameContent(newItem.story.recipient) &&
        isSelected == newItem.isSelected &&
        hasBeenNotified == newItem.hasBeenNotified
    }

    override fun getChangePayload(newItem: StoryModel): Any? {
      return if (story.recipient.hasSameContent(newItem.story.recipient) &&
        hasBeenNotified == newItem.hasBeenNotified &&
        newItem.isSelected != isSelected
      ) {
        0
      } else {
        null
      }
    }
  }

  private class StoryViewHolder(
    itemView: View,
    displayCheckBox: Boolean,
    onClick: OnClickedCallback<ContactSearchData.Story>,
    private val storyContextMenuCallbacks: StoryContextMenuCallbacks?
  ) : BaseRecipientViewHolder<StoryModel, ContactSearchData.Story>(itemView, displayCheckBox, DisplaySmsTag.NEVER, onClick) {
    override fun isSelected(model: StoryModel): Boolean = model.isSelected
    override fun getData(model: StoryModel): ContactSearchData.Story = model.story
    override fun getRecipient(model: StoryModel): Recipient = model.story.recipient

    override fun bindNumberField(model: StoryModel) {
      number.visible = true

      val count = if (model.story.recipient.isGroup) {
        model.story.recipient.participantIds.size
      } else {
        model.story.count
      }

      if (model.story.recipient.isMyStory && !model.hasBeenNotified) {
        number.setText(R.string.ContactSearchItems__tap_to_choose_your_viewers)
      } else {
        number.text = when {
          model.story.recipient.isGroup -> context.resources.getQuantityString(R.plurals.ContactSearchItems__group_story_d_viewers, count, count)
          model.story.recipient.isMyStory -> {
            if (model.story.privacyMode == DistributionListPrivacyMode.ALL_EXCEPT) {
              context.resources.getQuantityString(R.plurals.ContactSearchItems__my_story_s_dot_d_excluded, count, presentPrivacyMode(DistributionListPrivacyMode.ALL), count)
            } else {
              context.resources.getQuantityString(R.plurals.ContactSearchItems__my_story_s_dot_d_viewers, count, presentPrivacyMode(model.story.privacyMode), count)
            }
          }

          else -> context.resources.getQuantityString(R.plurals.ContactSearchItems__custom_story_d_viewers, count, count)
        }
      }
    }

    override fun bindAvatar(model: StoryModel) {
      if (model.story.recipient.isMyStory) {
        avatar.setFallbackPhotoProvider(MyStoryFallbackPhotoProvider(Recipient.self().getDisplayName(context), 40.dp))
        avatar.setAvatarUsingProfile(Recipient.self())
      } else {
        avatar.setFallbackPhotoProvider(Recipient.DEFAULT_FALLBACK_PHOTO_PROVIDER)
        super.bindAvatar(model)
      }
    }

    override fun bindLongPress(model: StoryModel) {
      if (storyContextMenuCallbacks == null) {
        return
      }

      itemView.setOnLongClickListener {
        val actions: List<ActionItem> = when {
          model.story.recipient.isMyStory -> getMyStoryContextMenuActions(model, storyContextMenuCallbacks)
          model.story.recipient.isGroup -> getGroupStoryContextMenuActions(model, storyContextMenuCallbacks)
          model.story.recipient.isDistributionList -> getPrivateStoryContextMenuActions(model, storyContextMenuCallbacks)
          else -> error("Unsupported story target. Not a group or distribution list.")
        }

        SignalContextMenu.Builder(itemView, itemView.rootView as ViewGroup)
          .offsetX(context.resources.getDimensionPixelSize(R.dimen.dsl_settings_gutter))
          .show(actions)

        true
      }
    }

    private fun getMyStoryContextMenuActions(model: StoryModel, callbacks: StoryContextMenuCallbacks): List<ActionItem> {
      return listOf(
        ActionItem(R.drawable.symbol_settings_android_24, context.getString(R.string.ContactSearchItems__story_settings)) {
          callbacks.onOpenStorySettings(model.story)
        }
      )
    }

    private fun getGroupStoryContextMenuActions(model: StoryModel, callbacks: StoryContextMenuCallbacks): List<ActionItem> {
      return listOf(
        ActionItem(R.drawable.symbol_minus_circle_24, context.getString(R.string.ContactSearchItems__remove_story)) {
          callbacks.onRemoveGroupStory(model.story, model.isSelected)
        }
      )
    }

    private fun getPrivateStoryContextMenuActions(model: StoryModel, callbacks: StoryContextMenuCallbacks): List<ActionItem> {
      return listOf(
        ActionItem(R.drawable.symbol_settings_android_24, context.getString(R.string.ContactSearchItems__story_settings)) {
          callbacks.onOpenStorySettings(model.story)
        },
        ActionItem(R.drawable.symbol_trash_24, context.getString(R.string.ContactSearchItems__delete_story), R.color.signal_colorError) {
          callbacks.onDeletePrivateStory(model.story, model.isSelected)
        }
      )
    }

    private fun presentPrivacyMode(privacyMode: DistributionListPrivacyMode): String {
      return when (privacyMode) {
        DistributionListPrivacyMode.ONLY_WITH -> context.getString(R.string.ContactSearchItems__only_share_with)
        DistributionListPrivacyMode.ALL_EXCEPT -> context.getString(R.string.ChooseInitialMyStoryMembershipFragment__all_except)
        DistributionListPrivacyMode.ALL -> context.getString(R.string.ChooseInitialMyStoryMembershipFragment__all_signal_connections)
      }
    }

    private class MyStoryFallbackPhotoProvider(private val name: String, private val targetSize: Int) : Recipient.FallbackPhotoProvider() {
      override fun getPhotoForLocalNumber(): FallbackContactPhoto {
        return GeneratedContactPhoto(name, R.drawable.symbol_person_40, targetSize)
      }
    }
  }

  /**
   * Recipient model
   */
  class RecipientModel(
    val knownRecipient: ContactSearchData.KnownRecipient,
    val isSelected: Boolean,
    val shortSummary: Boolean
  ) : MappingModel<RecipientModel>, FastScrollCharacterProvider {

    override fun getFastScrollCharacter(context: Context): CharSequence {
      val name = if (knownRecipient.recipient.isSelf) {
        context.getString(R.string.note_to_self)
      } else {
        knownRecipient.recipient.getDisplayName(context)
      }

      var letter = BreakIteratorCompat.getInstance().apply { setText(name) }.take(1)
      if (letter != null) {
        letter = letter.trim { it <= ' ' }
        if (letter.isNotEmpty()) {
          val firstChar = letter[0]
          if (Character.isLetterOrDigit(firstChar)) {
            return firstChar.uppercaseChar().toString()
          }
        }
      }

      return "#"
    }

    override fun areItemsTheSame(newItem: RecipientModel): Boolean {
      return newItem.knownRecipient == knownRecipient
    }

    override fun areContentsTheSame(newItem: RecipientModel): Boolean {
      return knownRecipient.recipient.hasSameContent(newItem.knownRecipient.recipient) && isSelected == newItem.isSelected
    }

    override fun getChangePayload(newItem: RecipientModel): Any? {
      return if (knownRecipient.recipient.hasSameContent(newItem.knownRecipient.recipient) && newItem.isSelected != isSelected) {
        0
      } else {
        null
      }
    }
  }

  class UnknownRecipientModel(val data: ContactSearchData.UnknownRecipient) : MappingModel<UnknownRecipientModel> {
    override fun areItemsTheSame(newItem: UnknownRecipientModel): Boolean = true

    override fun areContentsTheSame(newItem: UnknownRecipientModel): Boolean = data == newItem.data
  }

  private class UnknownRecipientViewHolder(
    itemView: View,
    private val onClick: OnClickedCallback<ContactSearchData.UnknownRecipient>,
    private val displayCheckBox: Boolean
  ) : MappingViewHolder<UnknownRecipientModel>(itemView) {

    private val checkbox: CheckBox = itemView.findViewById(R.id.check_box)
    private val name: FromTextView = itemView.findViewById(R.id.name)
    private val number: TextView = itemView.findViewById(R.id.number)

    override fun bind(model: UnknownRecipientModel) {
      checkbox.visible = displayCheckBox
      checkbox.isSelected = false
      name.setText(
        when (model.data.mode) {
          ContactSearchConfiguration.NewRowMode.NEW_CONVERSATION -> R.string.contact_selection_list__unknown_contact
          ContactSearchConfiguration.NewRowMode.BLOCK -> R.string.contact_selection_list__unknown_contact_block
          ContactSearchConfiguration.NewRowMode.ADD_TO_GROUP -> R.string.contact_selection_list__unknown_contact_add_to_group
        }
      )
      number.text = model.data.query
      itemView.setOnClickListener {
        onClick.onClicked(itemView, model.data, false)
      }
    }
  }

  private class KnownRecipientViewHolder(
    itemView: View,
    private val fixedContacts: Set<ContactSearchKey>,
    displayCheckBox: Boolean,
    displaySmsTag: DisplaySmsTag,
    private val displayPhoneNumber: DisplayPhoneNumber,
    onClick: OnClickedCallback<ContactSearchData.KnownRecipient>,
    private val onLongClick: OnLongClickedCallback<ContactSearchData.KnownRecipient>
  ) : BaseRecipientViewHolder<RecipientModel, ContactSearchData.KnownRecipient>(itemView, displayCheckBox, displaySmsTag, onClick), LetterHeaderDecoration.LetterHeaderItem {

    private var headerLetter: String? = null

    override fun isSelected(model: RecipientModel): Boolean = model.isSelected
    override fun getData(model: RecipientModel): ContactSearchData.KnownRecipient = model.knownRecipient
    override fun getRecipient(model: RecipientModel): Recipient = model.knownRecipient.recipient
    override fun bindNumberField(model: RecipientModel) {
      val recipient = getRecipient(model)
      if (model.knownRecipient.sectionKey == ContactSearchConfiguration.SectionKey.GROUP_MEMBERS) {
        number.text = model.knownRecipient.groupsInCommon.toDisplayText(context)
        number.visible = true
      } else if (model.shortSummary && recipient.isGroup) {
        val count = recipient.participantIds.size
        number.text = context.resources.getQuantityString(R.plurals.ContactSearchItems__group_d_members, count, count)
        number.visible = true
      } else if (displayPhoneNumber == DisplayPhoneNumber.ALWAYS && recipient.hasE164()) {
        number.text = recipient.requireE164()
        number.visible = true
      } else {
        super.bindNumberField(model)
      }

      headerLetter = model.knownRecipient.headerLetter
    }

    override fun bindCheckbox(model: RecipientModel) {
      checkbox.isEnabled = !fixedContacts.contains(model.knownRecipient.contactSearchKey)
    }

    override fun getHeaderLetter(): String? {
      return headerLetter
    }

    override fun bindLongPress(model: RecipientModel) {
      itemView.setOnLongClickListener { onLongClick.onLongClicked(itemView, model.knownRecipient) }
    }
  }

  /**
   * Base Recipient View Holder
   */
  abstract class BaseRecipientViewHolder<T : MappingModel<T>, D : ContactSearchData>(
    itemView: View,
    private val displayCheckBox: Boolean,
    private val displaySmsTag: DisplaySmsTag,
    val onClick: OnClickedCallback<D>
  ) : MappingViewHolder<T>(itemView) {

    protected val avatar: AvatarImageView = itemView.findViewById(R.id.contact_photo_image)
    protected val badge: BadgeImageView = itemView.findViewById(R.id.contact_badge)
    protected val checkbox: CheckBox = itemView.findViewById(R.id.check_box)
    protected val name: FromTextView = itemView.findViewById(R.id.name)
    protected val number: TextView = itemView.findViewById(R.id.number)
    protected val label: TextView = itemView.findViewById(R.id.label)
    protected val smsTag: View = itemView.findViewById(R.id.sms_tag)

    override fun bind(model: T) {
      checkbox.visible = displayCheckBox
      checkbox.isChecked = isSelected(model)

      itemView.setOnClickListener { onClick.onClicked(avatar, getData(model), isSelected(model)) }
      bindLongPress(model)

      if (payload.isNotEmpty()) {
        return
      }

      name.setText(getRecipient(model))
      badge.setBadgeFromRecipient(getRecipient(model))

      bindAvatar(model)
      bindNumberField(model)
      bindLabelField(model)
      bindSmsTagField(model)
    }

    protected open fun bindCheckbox(model: T) = Unit

    protected open fun bindAvatar(model: T) {
      avatar.setAvatar(getRecipient(model))
    }

    protected open fun bindNumberField(model: T) {
      number.visible = getRecipient(model).isGroup
      if (getRecipient(model).isGroup) {
        number.text = getRecipient(model).participantIds
          .take(10)
          .map { id -> Recipient.resolved(id) }
          .sortedWith(IsSelfComparator()).joinToString(", ") {
            if (it.isSelf) {
              context.getString(R.string.ConversationTitleView_you)
            } else {
              it.getShortDisplayName(context)
            }
          }
      }
    }

    protected open fun bindLabelField(model: T) {
      label.visible = false
    }

    protected open fun bindSmsTagField(model: T) {
      smsTag.visible = when (displaySmsTag) {
        DisplaySmsTag.DEFAULT -> isSmsContact(model)
        DisplaySmsTag.IF_NOT_REGISTERED -> isNotRegistered(model)
        DisplaySmsTag.NEVER -> false
      }
    }

    protected open fun bindLongPress(model: T) = Unit

    private fun isSmsContact(model: T): Boolean {
      return (getRecipient(model).isForceSmsSelection || getRecipient(model).isUnregistered) && !getRecipient(model).isDistributionList
    }

    private fun isNotRegistered(model: T): Boolean {
      return getRecipient(model).isUnregistered && !getRecipient(model).isDistributionList
    }

    abstract fun isSelected(model: T): Boolean
    abstract fun getData(model: T): D
    abstract fun getRecipient(model: T): Recipient
  }

  /**
   * Mapping Model for section headers
   */
  class HeaderModel(val header: ContactSearchData.Header) : MappingModel<HeaderModel> {
    override fun areItemsTheSame(newItem: HeaderModel): Boolean {
      return header.sectionKey == newItem.header.sectionKey
    }

    override fun areContentsTheSame(newItem: HeaderModel): Boolean {
      return areItemsTheSame(newItem) &&
        header.action?.icon == newItem.header.action?.icon &&
        header.action?.label == newItem.header.action?.label
    }
  }

  /**
   * Mapping Model for messages
   */
  class MessageModel(val message: ContactSearchData.Message) : MappingModel<MessageModel> {
    override fun areItemsTheSame(newItem: MessageModel): Boolean = message.contactSearchKey == newItem.message.contactSearchKey

    override fun areContentsTheSame(newItem: MessageModel): Boolean {
      return message == newItem.message
    }
  }

  /**
   * Mapping Model for threads
   */
  class ThreadModel(val thread: ContactSearchData.Thread) : MappingModel<ThreadModel> {
    override fun areItemsTheSame(newItem: ThreadModel): Boolean = thread.contactSearchKey == newItem.thread.contactSearchKey
    override fun areContentsTheSame(newItem: ThreadModel): Boolean {
      return thread == newItem.thread
    }
  }

  class EmptyModel(val empty: ContactSearchData.Empty) : MappingModel<EmptyModel> {
    override fun areItemsTheSame(newItem: EmptyModel): Boolean = true
    override fun areContentsTheSame(newItem: EmptyModel): Boolean = newItem.empty == empty
  }

  /**
   * Mapping Model for [ContactSearchData.GroupWithMembers]
   */
  class GroupWithMembersModel(val groupWithMembers: ContactSearchData.GroupWithMembers) : MappingModel<GroupWithMembersModel> {
    override fun areContentsTheSame(newItem: GroupWithMembersModel): Boolean = newItem.groupWithMembers == groupWithMembers

    override fun areItemsTheSame(newItem: GroupWithMembersModel): Boolean = newItem.groupWithMembers.contactSearchKey == groupWithMembers.contactSearchKey
  }

  /**
   * View Holder for section headers
   */
  private class HeaderViewHolder(itemView: View) : MappingViewHolder<HeaderModel>(itemView) {

    private val headerTextView: TextView = itemView.findViewById(R.id.section_header)
    private val headerActionView: MaterialButton = itemView.findViewById(R.id.section_header_action)

    override fun bind(model: HeaderModel) {
      headerTextView.setText(
        when (model.header.sectionKey) {
          ContactSearchConfiguration.SectionKey.STORIES -> R.string.ContactsCursorLoader_my_stories
          ContactSearchConfiguration.SectionKey.RECENTS -> R.string.ContactsCursorLoader_recent_chats
          ContactSearchConfiguration.SectionKey.INDIVIDUALS -> R.string.ContactsCursorLoader_contacts
          ContactSearchConfiguration.SectionKey.GROUPS -> R.string.ContactsCursorLoader_groups
          ContactSearchConfiguration.SectionKey.GROUP_MEMBERS -> R.string.ContactsCursorLoader_group_members
          ContactSearchConfiguration.SectionKey.CHATS -> R.string.ContactsCursorLoader__chats
          ContactSearchConfiguration.SectionKey.MESSAGES -> R.string.ContactsCursorLoader__messages
          ContactSearchConfiguration.SectionKey.GROUPS_WITH_MEMBERS -> R.string.ContactsCursorLoader_group_members
          ContactSearchConfiguration.SectionKey.CONTACTS_WITHOUT_THREADS -> R.string.ContactsCursorLoader_contacts
          else -> error("This section does not support HEADER")
        }
      )

      if (model.header.action != null) {
        headerActionView.visible = true
        headerActionView.setIconResource(model.header.action.icon)
        headerActionView.setText(model.header.action.label)
        headerActionView.setOnClickListener { model.header.action.action.run() }
      } else {
        headerActionView.visible = false
      }
    }
  }

  /**
   * Mapping Model for expandable content rows.
   */
  class ExpandModel(val expand: ContactSearchData.Expand) : MappingModel<ExpandModel> {
    override fun areItemsTheSame(newItem: ExpandModel): Boolean {
      return expand.contactSearchKey == newItem.expand.contactSearchKey
    }

    override fun areContentsTheSame(newItem: ExpandModel): Boolean {
      return areItemsTheSame(newItem)
    }
  }

  /**
   * View Holder for expandable content rows.
   */
  private class ExpandViewHolder(itemView: View, private val expandListener: (ContactSearchData.Expand) -> Unit) : MappingViewHolder<ExpandModel>(itemView) {
    override fun bind(model: ExpandModel) {
      itemView.setOnClickListener { expandListener.invoke(model.expand) }
    }
  }

  private class IsSelfComparator : Comparator<Recipient> {
    override fun compare(lhs: Recipient?, rhs: Recipient?): Int {
      val isLeftSelf = lhs?.isSelf == true
      val isRightSelf = rhs?.isSelf == true

      return if (isLeftSelf == isRightSelf) 0 else if (isLeftSelf) 1 else -1
    }
  }

  interface StoryContextMenuCallbacks {
    fun onOpenStorySettings(story: ContactSearchData.Story)
    fun onRemoveGroupStory(story: ContactSearchData.Story, isSelected: Boolean)
    fun onDeletePrivateStory(story: ContactSearchData.Story, isSelected: Boolean)
  }

  enum class DisplaySmsTag {
    DEFAULT,
    IF_NOT_REGISTERED,
    NEVER
  }

  enum class DisplayPhoneNumber {
    NEVER,
    ALWAYS
  }

  fun interface OnClickedCallback<D : ContactSearchData> {
    fun onClicked(view: View, data: D, isSelected: Boolean)
  }

  fun interface OnLongClickedCallback<D : ContactSearchData> {
    fun onLongClicked(view: View, data: D): Boolean
  }

  interface ClickCallbacks {
    fun onStoryClicked(view: View, story: ContactSearchData.Story, isSelected: Boolean)
    fun onKnownRecipientClicked(view: View, knownRecipient: ContactSearchData.KnownRecipient, isSelected: Boolean)
    fun onExpandClicked(expand: ContactSearchData.Expand)
    fun onUnknownRecipientClicked(view: View, unknownRecipient: ContactSearchData.UnknownRecipient, isSelected: Boolean) {
      throw NotImplementedError()
    }
  }

  interface LongClickCallbacks {
    fun onKnownRecipientLongClick(view: View, data: ContactSearchData.KnownRecipient): Boolean
  }

  class LongClickCallbacksAdapter : LongClickCallbacks {
    override fun onKnownRecipientLongClick(view: View, data: ContactSearchData.KnownRecipient): Boolean = false
  }
}
