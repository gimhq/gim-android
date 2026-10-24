package im.g.apps.core.bridge.fake

import im.g.apps.core.bridge.ChatKind
import im.g.apps.core.bridge.MediaRef
import im.g.apps.core.bridge.MembershipChangeKind
import im.g.apps.core.bridge.MessageContent
import im.g.apps.core.bridge.MessageItem
import im.g.apps.core.bridge.ReactionGroup
import im.g.apps.core.bridge.ReplyPreview
import im.g.apps.core.bridge.SendState
import im.g.apps.core.bridge.Session
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.UserProfile

/** Sample data for [FakeGimClient.withDemoData]: a sailing crew planning trips in European waters. */
object DemoData {
    const val SERVER_URL = "https://gim.example.eu"

    val session = Session("u-sofia", "d-demo", "sofia", "Sofia Costa")
    val me = UserProfile(session.userId, session.handle, session.displayName)
    val lukas = UserProfile("u-lukas", "lukas", "Lukas Berger")
    val ana = UserProfile("u-ana", "ana", "Ana Kovač")
    val nikos = UserProfile("u-nikos", "nikos", "Nikos Papadakis")

    const val DIRECT_CHAT_ID = "chat-lukas"
    const val GROUP_CHAT_ID = "chat-aegean"

    private const val MINUTE = 60_000L

    internal fun seed(client: FakeGimClient, now: Long) {
        client.seedSession(session)
        listOf(lukas, ana, nikos).forEach(client::addDirectoryUser)

        // 20 events spread over about two days, so the timeline shows date separators.
        val direct = Builder(client, DIRECT_CHAT_ID, now - 25 * MINUTE - 19 * 150 * MINUTE, 150 * MINUTE).directChat()
        client.seedChat(
            DIRECT_CHAT_ID, ChatKind.DIRECT, lukas.displayName, listOf(me, lukas),
            createdAt = now - 3 * 24 * 60 * MINUTE, events = direct, unread = 0,
        )
        val group = Builder(client, GROUP_CHAT_ID, now - 2 * MINUTE - 39 * 9 * MINUTE, 9 * MINUTE).groupChat()
        client.seedChat(
            GROUP_CHAT_ID, ChatKind.GROUP, "Aegean Crew", listOf(me, ana, lukas, nikos),
            createdAt = now - 2 * 24 * 60 * MINUTE, events = group, unread = 3,
        )
    }

    private fun Builder.directChat(): List<TimelineItem> {
        text(lukas, "Ciao Sofia! Are you still up for the delivery trip in May?")
        text(me, "Absolutely. Genoa to Split, right?")
        text(lukas, "Yes, a 44 ft sloop. The owner wants it in Split by the 20th.")
        text(me, "That's about 600 nm. Ten days with a buffer for weather?")
        text(lukas, "Sounds right. I'd route via Elba, the Strait of Messina and up the Adriatic.")
        text(me, "Messina can be lively. Let's time it with the tide tables.")
        text(lukas, "Good call. I'll check the currents.")
        image(lukas, "The boat at Porto Antico", 1600, 1200, 734_208, listOf(ReactionGroup("⛵", 1, true)))
        text(me, "She looks great! Is the rigging new?")
        text(lukas, "Replaced last autumn, survey report attached.")
        val survey = file(lukas, null, "survey-2026.pdf", "application/pdf", 482_113)
        text(me, "Thanks, reading it tonight.", replyTo = survey, edited = true)
        deleted(me)
        text(lukas, "Provisioning: I'll take care of water and diesel in Genoa.")
        text(me, "I'll bring charts for the Croatian coast and the spare autopilot.")
        text(lukas, "Perfect. Crew of three, I asked Nikos too.")
        text(me, "Great, he knows the Ionian well.", reactions = listOf(ReactionGroup("👍", 1, false)))
        text(lukas, "Let's do a call on Thursday to finalise.")
        text(me, "Thursday 18:00 CET works.")
        text(me, "See you then!", state = SendState.Delivered)
        return items
    }

    private fun Builder.groupChat(): List<TimelineItem> {
        membership(lukas, MembershipChangeKind.ADDED, actor = ana)
        membership(nikos, MembershipChangeKind.ADDED, actor = ana)
        text(ana, "Welcome aboard everyone! Week of 12 July, Piraeus to the Cyclades.")
        text(nikos, "I'll sort the charter paperwork at Alimos marina.")
        text(me, "Which islands are we aiming for?")
        text(ana, "Kea, Syros, Paros, Naxos, then back via Kythnos.")
        text(lukas, "Meltemi season: expect 25 knots in the afternoons.")
        text(nikos, "We'll do early starts and be in port by 15:00.")
        text(me, "Agreed. Reefing drill on day one?")
        text(ana, "Definitely.", reactions = listOf(ReactionGroup("👍", 3, true)))
        image(nikos, "Our boat for the week", 2048, 1365, 1_204_551, listOf(ReactionGroup("😍", 2, false)))
        text(lukas, "Lovely lines. Which yard built her?")
        text(nikos, "A 46 ft cruiser, four cabins, two heads.")
        text(me, "Who's cooking the first night?")
        val peka = text(ana, "I'll make my grandmother's peka if the oven allows.")
        text(lukas, "I'm in!", replyTo = peka)
        text(nikos, "The taverna in Vourkari on Kea is excellent too.")
        text(me, "Let's keep both options open.")
        file(
            ana, "Crew list for the charter company", "crew-list.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 18_432,
        )
        text(nikos, "Please fill in passport numbers by Friday.")
        text(me, "Done.", reactions = listOf(ReactionGroup("✅", 1, false)))
        text(lukas, "Done too.")
        text(ana, "Any wind forecast recommendations?")
        text(nikos, "I compare two models and trust neither too much 😄")
        text(me, "Same. Plus the coast guard VHF bulletins.")
        deleted(lukas)
        text(lukas, "Sorry, wrong chat.")
        text(ana, "Ha, no worries.")
        text(nikos, "The fuel dock at Syros closes at 14:00 on Sundays.")
        text(me, "Noted, we'll refuel on Kea then.", edited = true)
        text(ana, "Snorkelling gear: bring your own masks.")
        text(lukas, "I'll bring a spare.")
        val sunset = image(me, null, 1080, 1920, 987_340)
        text(ana, "Gorgeous!", replyTo = sunset)
        text(nikos, "Final plan: meet at Alimos gate B, 10:00 on the 12th.")
        text(me, "👌")
        text(lukas, "Can someone pick me up from the airport?")
        text(nikos, "I can, send me your flight number.")
        text(ana, "Also bring a headlamp for the night watches!")
        text(nikos, "Booked a table in Vourkari for the first night.")
        return items
    }

    /** Builds history events oldest first with evenly spaced timestamps; seeds media into [client]. */
    private class Builder(
        private val client: FakeGimClient,
        private val chatId: String,
        private var time: Long,
        private val step: Long,
    ) {
        val items = mutableListOf<TimelineItem>()

        fun text(
            from: UserProfile,
            body: String,
            replyTo: MessageItem? = null,
            reactions: List<ReactionGroup> = emptyList(),
            edited: Boolean = false,
            state: SendState = SendState.Read,
        ) = message(from, { MessageContent.Text(body) }, replyTo, reactions, edited, state)

        fun image(
            from: UserProfile,
            caption: String?,
            width: Int,
            height: Int,
            size: Long,
            reactions: List<ReactionGroup> = emptyList(),
        ) = message(from, { id ->
            val ref = MediaRef(chatId, id, "image/jpeg", size, hasThumbnail = true)
            client.seedMedia(ref, width, height)
            MessageContent.Image(ref, width, height, caption)
        }, reactions = reactions)

        fun file(from: UserProfile, caption: String?, fileName: String, mime: String, size: Long) =
            message(from, { id ->
                val ref = MediaRef(chatId, id, mime, size, hasThumbnail = false)
                client.seedMedia(ref, null, null)
                MessageContent.File(ref, fileName, caption)
            })

        fun deleted(from: UserProfile) = message(from, { MessageContent.Deleted })

        fun membership(user: UserProfile, change: MembershipChangeKind, actor: UserProfile?) {
            items += TimelineItem.MembershipChange(user.displayName, change, nextTime(), user.userId, actor?.userId)
        }

        private fun message(
            from: UserProfile,
            content: (messageId: String) -> MessageContent,
            replyTo: MessageItem? = null,
            reactions: List<ReactionGroup> = emptyList(),
            edited: Boolean = false,
            state: SendState = SendState.Read,
        ): MessageItem {
            val id = "$chatId-${items.size + 1}"
            val isOwn = from.userId == me.userId
            val message = MessageItem(
                messageId = id,
                chatId = chatId,
                sender = from,
                isOwn = isOwn,
                sentAtMs = nextTime(),
                content = content(id),
                replyTo = replyTo?.let {
                    ReplyPreview(it.messageId, it.sender.displayName, previewText(it.content), previewKind(it.content))
                },
                reactions = reactions,
                edited = edited,
                sendState = if (isOwn) state else null,
            )
            items += TimelineItem.Message(message)
            return message
        }

        private fun nextTime() = time.also { time += step }
    }
}
