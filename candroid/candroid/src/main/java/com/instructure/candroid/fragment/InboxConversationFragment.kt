/*
 * Copyright (C) 2018 - present  Instructure, Inc.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.instructure.candroid.fragment

import android.app.Activity.RESULT_OK
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.support.v4.content.ContextCompat
import android.support.v7.app.AlertDialog
import android.support.v7.widget.DividerItemDecoration
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.Toolbar
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.instructure.candroid.R
import com.instructure.candroid.adapter.InboxConversationAdapter
import com.instructure.candroid.events.ConversationUpdatedEvent
import com.instructure.candroid.events.MessageAddedEvent
import com.instructure.candroid.interfaces.MessageAdapterCallback
import com.instructure.candroid.router.RouteMatcher
import com.instructure.candroid.util.FileDownloadJobIntentService
import com.instructure.candroid.view.AttachmentView
import com.instructure.canvasapi2.apis.InboxApi
import com.instructure.canvasapi2.managers.InboxManager
import com.instructure.canvasapi2.models.*
import com.instructure.canvasapi2.utils.ApiPrefs
import com.instructure.canvasapi2.utils.pageview.PageView
import com.instructure.canvasapi2.utils.weave.WeaveJob
import com.instructure.canvasapi2.utils.weave.awaitApi
import com.instructure.canvasapi2.utils.weave.catch
import com.instructure.canvasapi2.utils.weave.tryWeave
import com.instructure.interactions.router.Route
import com.instructure.interactions.router.RouterParams
import com.instructure.pandautils.utils.*
import kotlinx.android.synthetic.main.fragment_inbox_conversation.*
import kotlinx.android.synthetic.main.recycler_swipe_refresh_layout.*
import kotlinx.android.synthetic.main.toolbar_layout.toolbar
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

@PageView(url = "conversations")
class InboxConversationFragment : ParentFragment() {

    private var scope by NullableStringArg(Const.SCOPE)
    private var conversation by ParcelableArg<Conversation>(key = Const.CONVERSATION)
    private var conversationId by LongArg(0L, Const.CONVERSATION_ID)

    private var conversationCall: WeaveJob? = null
    private var starCall: WeaveJob? = null
    private var archiveCall: WeaveJob? = null
    private var deleteConversationCall: WeaveJob? = null
    private var deleteMessageCall: WeaveJob? = null
    private var unreadCall: WeaveJob? = null

    private val adapter: InboxConversationAdapter by lazy {
        InboxConversationAdapter(context, conversation, mAdapterCallback)
    }

    private val menuListener = Toolbar.OnMenuItemClickListener { item ->
        when (item.itemId) {
            R.id.archive, R.id.unarchive -> toggleArchived()
            R.id.reply -> addMessage(adapter.topMessage, true)
            R.id.replyAll -> replyAllMessage()
            R.id.markAsUnread -> markConversationUnread()
            R.id.forward -> addMessage(adapter.forwardMessage, false)
            R.id.delete -> {
                val dialog = AlertDialog.Builder(context)
                        .setMessage(R.string.confirmDeleteConversation)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.delete) { _, _ -> deleteConversation() }
                        .create()

                dialog.setOnShowListener {
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(ThemePrefs.buttonColor)
                    dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setTextColor(ThemePrefs.buttonColor)
                }

                dialog.show()
            }
            else -> return@OnMenuItemClickListener false
        }
        true
    }

    private val mAdapterCallback = object : MessageAdapterCallback {
        override fun onAvatarClicked(user: BasicUser) = Unit

        override fun onAttachmentClicked(action: AttachmentView.AttachmentAction, attachment: Attachment) {
            when (action) {
                AttachmentView.AttachmentAction.REMOVE -> Unit // Do nothing

                AttachmentView.AttachmentAction.PREVIEW -> openMedia(attachment.contentType, attachment.url, attachment.filename, ApiPrefs.user!!)
                
                AttachmentView.AttachmentAction.DOWNLOAD -> {
                    if (PermissionUtils.hasPermissions(activity, PermissionUtils.WRITE_EXTERNAL_STORAGE)) {
                        FileDownloadJobIntentService.scheduleDownloadJob(context, attachment = attachment)
                    } else {
                        requestPermissions(PermissionUtils.makeArray(PermissionUtils.WRITE_EXTERNAL_STORAGE), PermissionUtils.WRITE_FILE_PERMISSION_REQUEST_CODE)
                    }
                }
            }
        }

        override fun onMessageAction(action: MessageAdapterCallback.MessageClickAction, message: Message) {
            when (action) {
                MessageAdapterCallback.MessageClickAction.REPLY -> addMessage(message, true)

                MessageAdapterCallback.MessageClickAction.FORWARD -> addMessage(message, false)

                MessageAdapterCallback.MessageClickAction.DELETE -> {
                    val dialog = AlertDialog.Builder(context)
                            .setMessage(R.string.confirmDeleteMessage)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.delete) { _, _ -> deleteMessage(message) }
                            .create()

                    dialog.setOnShowListener({
                        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(ThemePrefs.buttonColor)
                        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setTextColor(ThemePrefs.buttonColor)
                    })

                    dialog.show()
                }
            }
        }

        override fun getParticipantById(id: Long): BasicUser? = adapter.participants[id]

        override fun onRefreshFinished() {
            setRefreshing(false)
        }
    }

    override fun title(): String = getString(R.string.inbox)

    override fun onStart() {
        super.onStart()
        EventBus.getDefault().register(this)
    }

    override fun onStop() {
        super.onStop()
        EventBus.getDefault().unregister(this)
    }

    override fun onCreateView(inflater: LayoutInflater?, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return layoutInflater.inflate(R.layout.fragment_inbox_conversation, container, false)
    }

    override fun onViewCreated(view: View?, savedInstanceState: Bundle?) {
        when {
        // Setup from conversation ID
            conversationId != 0L -> {
                conversationCall = tryWeave {
                    conversation = awaitApi { InboxManager.getConversation(conversationId, true, it) }
                    conversationId = 0L
                    setupViews()
                } catch {
                    it.cause?.printStackTrace()
                    toast(R.string.errorConversationGeneric)
                    activity.onBackPressed()
                }
            }
        // Set up from conversation object
            else -> {
                setupViews()
            }
        }
    }

    override fun onDestroy() {
        starCall?.cancel()
        archiveCall?.cancel()
        deleteConversationCall?.cancel()
        deleteMessageCall?.cancel()
        unreadCall?.cancel()
        conversationCall?.cancel()
        super.onDestroy()
    }

    override fun applyTheme() {
        ViewStyler.themeToolbar(activity, toolbar, ThemePrefs.primaryColor, ThemePrefs.primaryTextColor)
    }

    private fun setupViews() {
        initToolbar()
        initConversationDetails()
        initAdapter()
    }

    private fun initAdapter() {
        configureRecyclerView(view!!, context, adapter, R.id.swipeRefreshLayout, R.id.emptyPandaView, R.id.listView)
        val dividerItemDecoration = DividerItemDecoration(listView.context, LinearLayoutManager.VERTICAL)
        dividerItemDecoration.setDrawable(ContextCompat.getDrawable(context, R.drawable.item_decorator_gray))
        listView.addItemDecoration(dividerItemDecoration)
    }

    private fun initToolbar() {
        toolbar.setupAsBackButton(this)
        toolbar.setTitle(R.string.message)
        toolbar.inflateMenu(R.menu.message_thread)

        if ("sent" == scope) {
            // We can't archive sent conversations
            val archive = toolbar.menu.findItem(R.id.archive)
            if (archive != null) {
                archive.isVisible = false
            }
            val unarchive = toolbar.menu.findItem(R.id.unarchive)
            if (unarchive != null) {
                unarchive.isVisible = false
            }
        }

        toolbar.setOnMenuItemClickListener(menuListener)
    }

    private fun initConversationDetails() {
        val conversation = conversation

        if (conversation.subject == null || conversation.subject.trim { it <= ' ' }.isEmpty()) {
            subjectView.setText(R.string.noSubject)
        } else {
            subjectView.text = conversation.subject
        }

        starred.setOnClickListener { toggleStarred() }
        starred.setImageResource(if (conversation.isStarred) R.drawable.vd_star_filled else R.drawable.vd_star)
        ColorUtils.colorIt(ThemePrefs.brandColor, starred.drawable)
        starred.alpha = 1f
        starred.isEnabled = true

        val menu = toolbar.menu
        // We don't want the archive option when it is in the sent folder, we've already toggled the visibility of those in initToolbar
        val isArchived = conversation.workflowState == Conversation.WorkflowState.ARCHIVED
        if (scope == null || scope != "sent") {
            menu.findItem(R.id.archive).isVisible = !isArchived
            menu.findItem(R.id.unarchive).isVisible = isArchived
        }

        // Set theme after menu changes, otherwise menu icons may retain original tint
        val textColor = ThemePrefs.primaryTextColor
        ToolbarColorizeHelper.colorizeToolbar(toolbar, textColor, activity)
    }

    private fun toggleStarred() {
        starCall?.cancel()
        val shouldStar = !conversation.isStarred
        tryWeave {
            starred.setImageResource(if (shouldStar) R.drawable.vd_star_filled else R.drawable.vd_star)
            ColorUtils.colorIt(ThemePrefs.brandColor, starred.drawable)
            starred.alpha = 0.35f
            starred.isEnabled = false
            awaitApi<Conversation> { InboxManager.starConversation(conversation.id, shouldStar, conversation.workflowState, it) }
            conversation.isStarred = shouldStar
            refreshConversationData()
            onConversationUpdated(false)
        } catch {
            toast(R.string.errorConversationGeneric)
            starred.setImageResource(if (!shouldStar) R.drawable.vd_star_filled else R.drawable.vd_star)
            ColorUtils.colorIt(ThemePrefs.brandColor, starred.drawable)
            refreshConversationData()
        }
    }

    private fun toggleArchived() {
        archiveCall?.cancel()
        val archive = conversation.workflowState != Conversation.WorkflowState.ARCHIVED
        archiveCall = tryWeave {
            awaitApi<Conversation> { InboxManager.archiveConversation(conversation.id, archive, it) }
            toast(if (archive) R.string.conversationArchived else R.string.conversationUnarchived)
            onConversationUpdated(true)
        } catch {
            toast(R.string.errorConversationGeneric)
        }
    }

    private fun deleteConversation() {
        deleteConversationCall?.cancel()
        deleteConversationCall = tryWeave {
            awaitApi<Conversation> { InboxManager.deleteConversation(conversation.id, it) }
            toast(R.string.deleted)
            onConversationUpdated(true)
        } catch {
            toast(R.string.errorConversationGeneric)
        }
    }

    private fun markConversationUnread() {
        unreadCall?.cancel()
        unreadCall = tryWeave {
            awaitApi<Void> { InboxManager.markConversationAsUnread(conversation.id, InboxApi.CONVERSATION_MARK_UNREAD, it) }
            onConversationUpdated(true)
        } catch {
            toast(R.string.errorConversationGeneric)
        }
    }

    private fun deleteMessage(message: Message) {
        deleteMessageCall?.cancel()
        deleteMessageCall = tryWeave {
            awaitApi<Conversation> { InboxManager.deleteMessages(conversation.id, listOf(message.id), it) }
            adapter.remove(message)
            if (adapter.size() > 0) {
                toast(R.string.deleted)
            } else {
                onConversationUpdated(true)
            }
        } catch {
            toast(R.string.errorConversationGeneric)
        }
    }

    private fun replyAllMessage() {
        val route = InboxComposeMessageFragment.makeRoute(
                true,
                conversation,
                adapter.participants.values.toList(),
                longArrayOf(),
                null)
        RouteMatcher.route(context, route)
    }

    private fun addMessage(message: Message, isReply: Boolean) {
        val route = InboxComposeMessageFragment.makeRoute(
                isReply,
                conversation,
                adapter.participants.values.toList(),
                adapter.getMessageChainIdsForMessage(message),
                message)
        RouteMatcher.route(context, route)
    }

    private fun refreshConversationData() {
        initConversationDetails()
    }

    private fun onConversationUpdated(goBack: Boolean) {
        EventBus.getDefault().postSticky(ConversationUpdatedEvent(conversation))
        if (goBack) activity.onBackPressed()
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN, sticky = true)
    fun onMessageChanged(event: MessageAddedEvent) {
        event.once(javaClass.simpleName + conversation.id + "_" + conversation.messageCount, { shouldUpdate ->
            if (shouldUpdate) adapter.refresh()
        })
    }

    companion object {

        @JvmStatic
        fun makeRoute(conversation: Conversation, scope: String?): Route {
            val bundle = Bundle().apply {
                putParcelable(Const.CONVERSATION, conversation)
                putString(Const.SCOPE, scope)
            }
            return Route(null, InboxConversationFragment::class.java, null, bundle)
        }

        @JvmStatic
        fun makeRoute(conversationId: Long): Route {
            val bundle = Bundle().apply { putLong(Const.CONVERSATION_ID, conversationId) }
            return Route(null, InboxConversationFragment::class.java, null, bundle)
        }

        @JvmStatic
        fun validateRoute(route: Route): Boolean {
            return route.arguments.containsKey(Const.CONVERSATION)
                    || route.arguments.containsKey(Const.CONVERSATION_ID)
                    || route.paramsHash.containsKey(RouterParams.CONVERSATION_ID)
        }

        @JvmStatic
        fun newInstance(route: Route) : InboxConversationFragment? {
            if (!validateRoute(route)) return null
            route.paramsHash[RouterParams.CONVERSATION_ID]?.let {
                route.arguments.putLong(Const.CONVERSATION_ID, it.toLong())
            }
            return InboxConversationFragment().withArgs(route.arguments)
        }

    }
}
