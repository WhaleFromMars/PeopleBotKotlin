import io.github.cdimascio.dotenv.Dotenv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.guild.GuildAvailableEvent
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.MemberCachePolicy
import net.dv8tion.jda.api.utils.cache.CacheFlag

object PeopleBot : ListenerAdapter() {
    val dotEnv = Dotenv.load()
    val GUILD_ID = dotEnv["DISCORD_GUILD_ID"]
    private val token = dotEnv["DISCORD_BOT_TOKEN"]

    private var isStreaming = false

    val jda = JDABuilder.create(
        token,
        GatewayIntent.GUILD_MEMBERS,
        GatewayIntent.GUILD_MESSAGE_REACTIONS,
        GatewayIntent.GUILD_MESSAGES,
        GatewayIntent.MESSAGE_CONTENT,
        GatewayIntent.GUILD_VOICE_STATES
    ).disableCache(
        CacheFlag.ACTIVITY,
        CacheFlag.EMOJI,
        CacheFlag.STICKER,
        CacheFlag.CLIENT_STATUS,
        CacheFlag.ONLINE_STATUS,
        CacheFlag.SCHEDULED_EVENTS
    ).setMemberCachePolicy(MemberCachePolicy.VOICE).addEventListeners(this).build()

    init {
        require(token.isNotEmpty()) { "Missing environment variable: DISCORD_BOT_TOKEN" }
        require(GUILD_ID.isNotEmpty()) { "Missing environment variable: DISCORD_GUILD_ID" }
        registerCommands()
    }

    override fun onGuildAvailable(event: GuildAvailableEvent) {
        Cache
        SpotifyHelper
    }

    private fun registerCommands() {
        jda.updateCommands().addCommands(
            Commands.slash("play", "Adds a song to the queue")
                .addOption(OptionType.STRING, "song", "requires the song name or spotify url", true, true)
                .setGuildOnly(true),
            Commands.slash(
                "setnowplayingchannel", "sets the channel to place the embed messages for current track and the queue"
            ).addOptions(
                OptionData(
                    OptionType.CHANNEL,
                    "channel",
                    "requires a text channel",
                    true
                ).setChannelTypes(ChannelType.TEXT)
            )
                .setGuildOnly(true),
            Commands.slash("clearqueue", "Clears the queue").setGuildOnly(true),
            Commands.slash("remove", "Removes a song from the queue").addOption(
                OptionType.STRING,
                "index",
                "requires the index or name of the song in the queue",
                true,
                true
            ).setGuildOnly(true),
            Commands.slash("who", "queries who requested the current / specified song")
                .addOption(
                    OptionType.STRING,
                    "index",
                    "requires the index or name of the song in the queue",
                    false,
                    true
                ).setGuildOnly(true)
        ).apply { queue() }
    }

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return
        if (guild.id != GUILD_ID) return event.reply("This bot is configured for a different server")
            .setEphemeral(true).queue()

        CoroutineScope(Dispatchers.IO).launch {
            when (event.name) {
                "play" -> PeopleCommands.playSlashCommand(event)
                "setnowplayingchannel" -> PeopleCommands.nowPlayingChannelSlashCommand(event)
                "remove" -> PeopleCommands.removeSlashCommand(event)
                "clearqueue" -> PeopleCommands.clearQueueSlashCommand(event)
                "who" -> PeopleCommands.whoSlashCommand(event)
            }
        }
    }

    override fun onMessageReactionAdd(event: MessageReactionAddEvent) {
        if (event.guild.id != GUILD_ID) return
        if (event.messageId != Cache.messageID) return
        if (event.user?.id == jda.selfUser.id) return
        val user = event.user ?: return

        val emoji = event.emoji

        event.channel.asTextChannel().removeReactionById(event.messageId, emoji, user).queue()

        //if the user isnt in the same vc as the bot, ignore their reaction
        if (event.member?.voiceState?.channel != event.guild.selfMember.voiceState?.channel) return
        if (SpotifyPlayer.currentTrack == null) return
        when (emoji) {
            Emoji.fromUnicode("\uD83D\uDD00") -> SpotifyPlayer.shuffle()
            Emoji.fromUnicode("\u23EE\uFE0F") -> SpotifyPlayer.playPrevious()
            Emoji.fromUnicode("\u23EF\uFE0F") -> SpotifyPlayer.togglePause()
            Emoji.fromUnicode("\u23ED\uFE0F") -> SpotifyPlayer.playNext()
            Emoji.fromUnicode("\uD83D\uDD01") -> SpotifyPlayer.toggleRepeat()
            Emoji.fromUnicode("\u23F9\uFE0F") -> SpotifyPlayer.shutdownPlayer()
        }
    }

    override fun onCommandAutoCompleteInteraction(event: CommandAutoCompleteInteractionEvent) {
        if (event.guild?.id != GUILD_ID) return

        CoroutineScope(Dispatchers.IO).launch {
            PeopleCommands.handleAutoComplete(event)
        }
    }

    fun leaveVoiceChannel(guild: Guild) {
        val audioManager = guild.audioManager
        stopStreaming(guild)
        audioManager.closeAudioConnection()
    }

    fun startStreaming(guild: Guild) {
        if (isStreaming) return

        isStreaming = true
        val audioManager = guild.audioManager


        if (!AudioStreamHandler.startCapture()) {
            isStreaming = false
            println("Failed to start audio capture.")
            return
        }
        audioManager.sendingHandler = AudioStreamHandler

        println("Started streaming audio from CABLE Output (VB-Audio Virtual Cable)")
    }

    fun stopStreaming(guild: Guild) {
        if (!isStreaming) return

        isStreaming = false
        AudioStreamHandler.stopCapture()
        guild.audioManager.sendingHandler = null
        println("Stopped streaming")
    }
}
