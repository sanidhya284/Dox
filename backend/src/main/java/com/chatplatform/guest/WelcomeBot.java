package com.chatplatform.guest;

import com.chatplatform.auth.service.AuthService;
import com.chatplatform.conversation.service.ConversationService;
import com.chatplatform.message.event.MessageCreatedEvent;
import com.chatplatform.message.service.MessageService;
import com.chatplatform.shared.ratelimit.FixedWindowRateLimiter;
import com.chatplatform.user.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.UUID;

/**
 * The welcome bot (ADR-0012): a system account ("dox-bot", no usable password) that greets every guest.
 * Seeded idempotently at startup, whatever the profile. When someone writes in a conversation the bot is in,
 * the bot replies once with a fixed text (at most once per 10 s per conversation) and marks the message read.
 *
 * <p>It reacts AFTER_COMMIT (never to a send that rolled back), on its own single thread (never on the request
 * thread). It replies BEFORE it moves its read cursor, so a read cursor at seq N means message N is fully
 * handled (tests rely on this). Its messages go through {@link MessageService#sendAsSystem}: the same
 * transaction and seq code as any send.
 */
@Component
public class WelcomeBot implements ApplicationRunner, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(WelcomeBot.class);

    static final String DISPLAY_NAME = "Dox bot";
    static final String WELCOME = "Hi, welcome to Dox! I'm the demo bot. This is your own private demo: send a "
            + "message and I'll mark it read. Your guest account and its conversations are removed after 24 hours.";
    static final String GROUP_TITLE = "Your demo group";
    static final String GROUP_WELCOME = "This is a group conversation. Guests can't create groups; sign up "
            + "for a free account to start your own.";
    static final String REPLY = "Thanks! I'm only a bot, so this is the one thing I say. Sign up to chat with "
            + "real people: search for them by username.";

    private final UserService userService;
    private final ConversationService conversationService;
    private final MessageService messageService;
    private final FixedWindowRateLimiter replies =
            new FixedWindowRateLimiter(1, Duration.ofSeconds(10), 10_000, System::currentTimeMillis);
    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    private volatile UUID botId;

    public WelcomeBot(UserService userService, ConversationService conversationService, MessageService messageService) {
        this.userService = userService;
        this.conversationService = conversationService;
        this.messageService = messageService;
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("welcome-bot-");
        // A full queue drops the reaction (logged): the bot is best effort, never back-pressure on sends.
        executor.setRejectedExecutionHandler((r, e) -> log.warn("Welcome bot queue full; reaction dropped"));
        executor.initialize();
    }

    /** Off only in the unit-test profile, whose H2 database has no schema (Flyway is disabled there). */
    @org.springframework.beans.factory.annotation.Value("${guest.seed-bot:true}")
    private boolean seedOnStartup;

    @Override
    public void run(ApplicationArguments args) {
        if (seedOnStartup) {
            id();
        }
    }

    /** The bot's user id; creates the account on first use. */
    public synchronized UUID id() {
        if (botId == null) {
            botId = userService.ensureBot(AuthService.BOT_USERNAME, DISPLAY_NAME).id();
        }
        return botId;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessage(MessageCreatedEvent event) {
        UUID bot = botId;
        if (bot == null || bot.equals(event.message().senderId())) {
            return;
        }
        executor.execute(() -> react(bot, event.message().conversationId(), event.message().seq()));
    }

    private void react(UUID bot, UUID conversationId, long seq) {
        try {
            if (!conversationService.activeParticipantIds(conversationId).contains(bot)) {
                return;
            }
            if (replies.tryAcquire(conversationId.toString()).allowed()) {
                messageService.sendAsSystem(conversationId, bot, REPLY);
            }
            conversationService.advanceCursor(conversationId, bot, "read", seq);
        } catch (RuntimeException e) {
            // E.g. the conversation was removed meanwhile. The bot is best effort.
            log.debug("Welcome bot skipped message {} in {}: {}", seq, conversationId, e.toString());
        }
    }

    @Override
    public void destroy() {
        executor.shutdown();
    }
}
