package im.g.apps.core.bridge.fake

import im.g.apps.core.bridge.ChatId
import im.g.apps.core.bridge.ChatKind
import im.g.apps.core.bridge.MessageContent
import im.g.apps.core.bridge.ReactionGroup
import im.g.apps.core.bridge.ReplyPreview
import im.g.apps.core.bridge.SendStatus
import im.g.apps.core.bridge.Session
import im.g.apps.core.bridge.SystemEventKind
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.User

/** Sample data for [FakeGimClient.withDemoData]: a sailing crew planning trips in European waters. */
object DemoData {
    val session = Session("u-sofia", "d-demo", "sofia", "Sofia Costa", "https://gim.example.eu")
    val me = User(session.userId, session.handle, session.displayName)
    val lukas = User("u-lukas", "lukas", "Lukas Berger")
    val ana = User("u-ana", "ana", "Ana Kovač")
    val nikos = User("u-nikos", "nikos", "Nikos Papadakis")

    const val DIRECT_CHAT_ID: ChatId = "chat-lukas"
    const val GROUP_CHAT_ID: ChatId = "chat-aegean"

    private const val MINUTE = 60_000L

    internal fun seed(client: FakeGimClient, now: Long) {
        client.seedSession(session)
        listOf(lukas, ana, nikos).forEach(client::addDirectoryUser)
        client.seedMedia("media-demo-1", "image/jpeg", 1600, 1200)
        client.seedMedia("media-demo-2", "image/jpeg", 2048, 1365)
        client.seedMedia("media-demo-3", "image/jpeg", 1080, 1920)
        client.seedMedia("media-demo-4", "application/pdf", null, null)
        client.seedMedia("media-demo-5", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", null, null)

        val direct = directChat(now - 25 * MINUTE - 19 * 11 * MINUTE, 11 * MINUTE)
        client.seedChat(
            DIRECT_CHAT_ID, ChatKind.DIRECT, lukas.displayName, listOf(me, lukas),
            createdAt = now - 3 * 24 * 60 * MINUTE, history = direct, unread = 0,
        )
        val group = groupChat(now - 2 * MINUTE - 39 * 9 * MINUTE, 9 * MINUTE)
        client.seedChat(
            GROUP_CHAT_ID, ChatKind.GROUP, "Aegean Crew", listOf(me, ana, lukas, nikos),
            createdAt = now - 2 * 24 * 60 * MINUTE, history = group, unread = 3,
        )
    }

    private fun directChat(start: Long, step: Long): List<TimelineItem> = Builder(DIRECT_CHAT_ID, start, step).run {
        text(lukas, "Ciao Sofia! Are you still up for the delivery trip in May?")
        text(me, "Absolutely. Genoa to Split, right?")
        text(lukas, "Yes, a 44 ft sloop. The owner wants it in Split by the 20th.")
        text(me, "That's about 600 nm. Ten days with a buffer for weather?")
        text(lukas, "Sounds right. I'd route via Elba, the Strait of Messina and up the Adriatic.")
        text(me, "Messina can be lively. Let's time it with the tide tables.")
        text(lukas, "Good call. I'll check the currents.")
        image(lukas, "The boat at Porto Antico", "media-demo-1", 1600, 1200, 734_208, listOf(ReactionGroup("⛵", 1, true)))
        text(me, "She looks great! Is the rigging new?")
        text(lukas, "Replaced last autumn, survey report attached.")
        val survey = file(lukas, null, "survey-2026.pdf", "application/pdf", 482_113, "media-demo-4")
        text(me, "Thanks, reading it tonight.", replyTo = survey, edited = true)
        deleted(me)
        text(lukas, "Provisioning: I'll take care of water and diesel in Genoa.")
        text(me, "I'll bring charts for the Croatian coast and the spare autopilot.")
        text(lukas, "Perfect. Crew of three, I asked Nikos too.")
        text(me, "Great, he knows the Ionian well.", reactions = listOf(ReactionGroup("👍", 1, false)))
        text(lukas, "Let's do a call on Thursday to finalise.")
        text(me, "Thursday 18:00 CET works.")
        text(me, "See you then!", status = SendStatus.DELIVERED)
        build()
    }

    private fun groupChat(start: Long, step: Long): List<TimelineItem> = Builder(GROUP_CHAT_ID, start, step).run {
        system(SystemEventKind.CHAT_CREATED, "${ana.displayName} created the group")
        system(SystemEventKind.MEMBER_ADDED, "${nikos.displayName} was added")
        text(ana, "Welcome aboard everyone! Week of 12 July, Piraeus to the Cyclades.")
        text(nikos, "I'll sort the charter paperwork at Alimos marina.")
        text(me, "Which islands are we aiming for?")
        text(ana, "Kea, Syros, Paros, Naxos, then back via Kythnos.")
        text(lukas, "Meltemi season: expect 25 knots in the afternoons.")
        text(nikos, "We'll do early starts and be in port by 15:00.")
        text(me, "Agreed. Reefing drill on day one?")
        text(ana, "Definitely.", reactions = listOf(ReactionGroup("👍", 3, true)))
        image(nikos, "Our boat for the week", "media-demo-2", 2048, 1365, 1_204_551, listOf(ReactionGroup("😍", 2, false)))
        text(lukas, "Lovely lines. Which yard built her?")
        text(nikos, "A 46 ft cruiser, four cabins, two heads.")
        text(me, "Who's cooking the first night?")
        val peka = text(ana, "I'll make my grandmother's peka if the oven allows.")
        text(lukas, "I'm in!", replyTo = peka)
        text(nikos, "The taverna in Vourkari on Kea is excellent too.")
        text(me, "Let's keep both options open.")
        file(ana, "Crew list for the charter company", "crew-list.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 18_432, "media-demo-5")
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
        val sunset = image(me, null, "media-demo-3", 1080, 1920, 987_340)
        text(ana, "Gorgeous!", replyTo = sunset)
        text(nikos, "Final plan: meet at Alimos gate B, 10:00 on the 12th.")
        text(me, "👌")
        text(lukas, "Can someone pick me up from the airport?")
        text(nikos, "I can, send me your flight number.")
        text(ana, "Also bring a headlamp for the night watches!")
        text(nikos, "Booked a table in Vourkari for the first night.")
        build()
    }

    /** Builds a timeline oldest-first with evenly spaced timestamps; [build] returns it newest-first. */
    private class Builder(private val chatId: ChatId, private var time: Long, private val step: Long) {
        private val items = mutableListOf<TimelineItem>()

        fun text(
            from: User,
            body: String,
            replyTo: TimelineItem.Message? = null,
            reactions: List<ReactionGroup> = emptyList(),
            edited: Boolean = false,
            status: SendStatus = SendStatus.READ,
        ) = message(from, MessageContent.Text(body), replyTo, reactions, edited, status)

        fun image(
            from: User,
            caption: String?,
            mediaId: String,
            width: Int,
            height: Int,
            size: Long,
            reactions: List<ReactionGroup> = emptyList(),
        ) = message(from, MessageContent.Image(caption, "image/jpeg", width, height, size, null, mediaId), reactions = reactions)

        fun file(from: User, caption: String?, fileName: String, mime: String, size: Long, mediaId: String) =
            message(from, MessageContent.File(caption, fileName, mime, size, mediaId))

        fun deleted(from: User) = message(from, MessageContent.Deleted, deleted = true)

        fun system(kind: SystemEventKind, text: String) {
            items += TimelineItem.System(nextId(), nextTime(), kind, text)
        }

        fun build(): List<TimelineItem> = items.asReversed().toList()

        private fun message(
            from: User,
            content: MessageContent,
            replyTo: TimelineItem.Message? = null,
            reactions: List<ReactionGroup> = emptyList(),
            edited: Boolean = false,
            status: SendStatus = SendStatus.READ,
            deleted: Boolean = false,
        ): TimelineItem.Message {
            val sentAt = nextTime()
            val fromMe = from.id == me.id
            val message = TimelineItem.Message(
                id = nextId(),
                chatId = chatId,
                sender = from,
                fromMe = fromMe,
                sentAt = sentAt,
                content = content,
                replyTo = replyTo?.let { ReplyPreview(it.id, it.sender.displayName, snippet(it.content)) },
                reactions = reactions,
                editedAt = if (edited) sentAt + MINUTE else null,
                deleted = deleted,
                status = if (fromMe) status else null,
            )
            items += message
            return message
        }

        private fun nextId() = "$chatId-${items.size + 1}"

        private fun nextTime() = time.also { time += step }
    }
}
