import com.mongodb.client.model.*
import com.mongodb.reactivestreams.client.MongoClient
import com.mongodb.reactivestreams.client.MongoClients
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel
import net.dv8tion.jda.api.entities.channel.forums.ForumTag
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.requests.GatewayIntent
import org.bson.Document
import org.bson.types.BasicBSONList
import org.litote.kmongo.coroutine.CoroutineClient
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.eq
import org.litote.kmongo.reactivestreams.KMongo
import org.litote.kmongo.reactivestreams.getCollection
import org.litote.kmongo.setTo
import java.time.Instant

@Serializable

data class LoggedMessage(
    @SerialName("_id") val messageId: Long,
    val authorId: Long,
    val channelId: Long,
    val parentChannelId: Long?,
    val messageHistory: MutableMap<Long, String>, // UnixTimeStamp, Content
    val isDeleted: Boolean = false,
) {
    fun getLatestMessageTimestamp() = messageHistory.keys.maxOrNull() ?: 0
}

data class GuildThread(
    @SerialName("_id") val threadId: Long,
    val ownerId: Long,
    val channelId: Long,
    val guildId: Long,
    val tags: List<Long>,
    val isDeleted: Boolean = false,
)

suspend fun main(args: Array<String>) {
    val intents = listOf(
        GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_VOICE_STATES, GatewayIntent.GUILD_EMOJIS_AND_STICKERS
    )

    val api = JDABuilder.createDefault("OTgyNjAwNDk2MTg5Njc3NTY4.G9wJ3N.HPSf60ajVrKtkPXjUxRzPepZ9JRL18SEosg0zQ")
        .enableIntents(intents).build().awaitReady()

    val mongoClient =
        KMongo.createClient("mongodb+srv://test-learnspigot:LHboGwzHJwg1lYUA@learnspigot.hrrdd5c.mongodb.net/?retryWrites=true&w=majority").coroutine


    val scraper = Scraper(mongoClient)

    val helpChannel = api.getForumChannelById(1106293708632096819)!!

    println("Starting scraping")

    scraper.scrapeForumChannel(helpChannel)
}

class Scraper(mongoClient: CoroutineClient) {

    private val database = mongoClient.getDatabase("learnspigot-test")

    private val messagesCollection = database.getCollection<LoggedMessage>()
    private val threadsCollections = database.getCollection<GuildThread>()

    private fun convertMessage(message: Message): LoggedMessage {
        val time = Instant.ofEpochSecond(message.timeCreated.toEpochSecond())

        val channel = message.channel
        return LoggedMessage(
            messageId = message.idLong,
            authorId = message.author.idLong,
            channelId = channel.idLong,
            parentChannelId = if (channel.type.isThread) message.channel.asThreadChannel().parentChannel.idLong else null,
            messageHistory = mutableMapOf(time.toEpochMilli() to message.contentRaw)
        )
    }

    private suspend fun uploadMessages(messages: List<Message>) {
        println("Uploading ${messages.size} messages")

        val models = messages.map(::convertMessage).map {
            ReplaceOneModel(
                LoggedMessage::messageId eq it.messageId, it, ReplaceOptions().upsert(true)
            )
        }
        messagesCollection.bulkWrite(models)

        println("Uploaded ${messages.size} messages")
    }

    private suspend fun updateThread(threadChannel: ThreadChannel) {
        val tags = threadChannel.appliedTags.map(ForumTag::getIdLong)

        val thread = GuildThread(
            threadId = threadChannel.idLong,
            ownerId = threadChannel.ownerIdLong,
            channelId = threadChannel.parentChannel.idLong,
            guildId = threadChannel.guild.idLong,
            isDeleted = false,
            tags = tags
        )


        threadsCollections.replaceOne(
            GuildThread::threadId eq threadChannel.idLong, thread, ReplaceOptions().upsert(true)
        )
        println("Updated thread ${threadChannel.name}")
    }


    suspend fun scrapeForumChannel(forumChannel: ForumChannel) {

        val openThreads = forumChannel.threadChannels

        val closedThreads = forumChannel.retrieveArchivedPublicThreadChannels().complete()


        val threads = openThreads + closedThreads


        coroutineScope {
            threads.forEach { channel ->

                launch {
                    val startMessage = runCatching { channel.retrieveStartMessage().complete() }.getOrNull()
                    startMessage?.let { uploadMessages(listOf(startMessage)) }

                    println("Scraping thread ${channel.name}")
                    updateThread(channel)
                    scrapeMessageChannel(channel)

                }
            }
        }

        println("Scraping ${threads.size} threads")

//

    }

    tailrec suspend fun scrapeMessageChannel(
        channel: MessageChannel, beforeId: Long = channel.latestMessageIdLong
    ) {
        val AMOUNT = 100
        val history = channel.getHistoryBefore(beforeId, AMOUNT).complete()
        val messages = history.retrievedHistory
        println("Scraping ${messages.size} messages' worth of history.")

        if (messages.isEmpty()) return
        uploadMessages(messages)
    
        if (history.retrievedHistory.size < AMOUNT) return
        scrapeMessageChannel(channel, messages.last().idLong)
    }
}

