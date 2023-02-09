package org.thoughtcrime.securesms.service.webrtc.state

import com.annimon.stream.OptionalLong
import org.signal.ringrtc.CallId
import org.signal.ringrtc.GroupCall
import org.thoughtcrime.securesms.events.CallParticipant
import org.thoughtcrime.securesms.events.CallParticipantId
import org.thoughtcrime.securesms.events.WebRtcViewModel
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.ringrtc.RemotePeer

/**
 * General state of ongoing calls.
 */
data class CallInfoState(
  var callState: WebRtcViewModel.State = WebRtcViewModel.State.IDLE,
  var callRecipient: Recipient = Recipient.UNKNOWN,
  var callConnectedTime: Long = -1,
  @get:JvmName("getRemoteCallParticipantsMap") var remoteParticipants: MutableMap<CallParticipantId, CallParticipant> = mutableMapOf(),
  var peerMap: MutableMap<Int, RemotePeer> = mutableMapOf(),
  var activePeer: RemotePeer? = null,
  var groupCall: GroupCall? = null,
  @get:JvmName("getGroupCallState") var groupState: WebRtcViewModel.GroupCallState = WebRtcViewModel.GroupCallState.IDLE,
  var identityChangedRecipients: MutableSet<RecipientId> = mutableSetOf(),
  var remoteDevicesCount: OptionalLong = OptionalLong.empty(),
  var participantLimit: Long? = null
) {

  val remoteCallParticipants: List<CallParticipant>
    get() = ArrayList(remoteParticipants.values)

  fun getRemoteCallParticipant(recipient: Recipient): CallParticipant? {
    return getRemoteCallParticipant(CallParticipantId(recipient))
  }

  fun getRemoteCallParticipant(callParticipantId: CallParticipantId): CallParticipant? {
    return remoteParticipants[callParticipantId]
  }

  fun getPeer(hashCode: Int): RemotePeer? {
    return peerMap[hashCode]
  }

  fun getPeerByCallId(callId: CallId): RemotePeer? {
    return peerMap.values.firstOrNull { it.callId == callId }
  }

  fun requireActivePeer(): RemotePeer {
    return activePeer!!
  }

  fun requireGroupCall(): GroupCall {
    return groupCall!!
  }

  fun duplicate(): CallInfoState = copy(
    remoteParticipants = remoteParticipants.toMutableMap(),
    peerMap = peerMap.toMutableMap(),
    identityChangedRecipients = identityChangedRecipients.toMutableSet()
  )
}
