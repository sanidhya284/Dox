package com.chatplatform.guest;

import com.chatplatform.auth.service.TokenService;
import com.chatplatform.conversation.service.ConversationService;
import com.chatplatform.demo.DemoSeeder;
import com.chatplatform.message.service.MessageService;
import com.chatplatform.realtime.service.WsTicketService;
import com.chatplatform.shared.event.UserSessionsEndedEvent;
import com.chatplatform.shared.event.UserSessionsEndedEvent.Reason;
import com.chatplatform.user.dto.UserSummary;
import com.chatplatform.user.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Removes accounts (ADR-0012): expired guests (the cleanup job) and abusive accounts (the runbook). Both use
 * the same step, {@link #remove}, in one transaction per account:
 * <ol>
 *   <li>conversations whose other members (ever) are only system accounts (bot, demo) or guests are deleted,
 *       messages first;</li>
 *   <li>every other conversation is left: its messages stay, with contiguous seq, attributed to the removed
 *       account, because deleting them would leave seq gaps that break every other member's sync;</li>
 *   <li>the user row is anonymized (unique "deleted-" username and email, no usable password);</li>
 *   <li>refresh tokens and WebSocket tickets are revoked; after commit, sockets close (4008 or 4002).</li>
 * </ol>
 * Expired guests are claimed with FOR UPDATE SKIP LOCKED, so two instances never remove the same guest and never
 * wait on each other.
 */
@Service
public class AccountRemovalService {

    private static final Logger log = LoggerFactory.getLogger(AccountRemovalService.class);
    static final int MAX_PER_RUN = 500;

    private final UserService userService;
    private final ConversationService conversationService;
    private final MessageService messageService;
    private final TokenService tokenService;
    private final WsTicketService wsTicketService;
    private final ApplicationEventPublisher events;
    private final WelcomeBot bot;
    private final TransactionTemplate tx;
    private final Duration guestTtl;

    public AccountRemovalService(UserService userService, ConversationService conversationService,
                                 MessageService messageService, TokenService tokenService,
                                 WsTicketService wsTicketService, ApplicationEventPublisher events, WelcomeBot bot,
                                 PlatformTransactionManager transactionManager,
                                 @Value("${guest.ttl:PT24H}") Duration guestTtl) {
        this.userService = userService;
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.tokenService = tokenService;
        this.wsTicketService = wsTicketService;
        this.events = events;
        this.bot = bot;
        this.tx = new TransactionTemplate(transactionManager);
        this.guestTtl = guestTtl;
    }

    /** Removes guests older than guest.ttl, one transaction each, at most MAX_PER_RUN. Returns how many. */
    public int removeExpiredGuests() {
        Instant cutoff = Instant.now().minus(guestTtl);
        int removed = 0;
        while (removed < MAX_PER_RUN) {
            Boolean one = tx.execute(status -> {
                Optional<UUID> guest = userService.lockNextExpiredGuest(cutoff);
                guest.ifPresent(id -> remove(id, Reason.GUEST_EXPIRED, "Deleted guest"));
                return guest.isPresent();
            });
            if (!Boolean.TRUE.equals(one)) {
                break;
            }
            removed++;
        }
        if (removed > 0) {
            log.info("Removed {} expired guest account(s)", removed);
        }
        return removed;
    }

    /** Runbook, "remove an abusive account": the same step as the guest cleanup. */
    public void removeAccount(String username) {
        tx.executeWithoutResult(status -> {
            UUID id = userService.lockLiveByUsername(username)
                    .orElseThrow(() -> new IllegalArgumentException("No live account with that username"));
            if (id.equals(bot.id())) {
                throw new IllegalArgumentException("The bot account can't be removed");
            }
            remove(id, Reason.ACCOUNT_REMOVED, "Deleted user");
        });
        log.info("Removed account {} (runbook)", username);
    }

    /** The one removal step; runs inside the caller's transaction with the user row locked. */
    private void remove(UUID userId, Reason reason, String displayName) {
        Map<UUID, Set<UUID>> conversations = conversationService.membersOfConversationsOf(userId);
        Set<UUID> everyone = new HashSet<>();
        conversations.values().forEach(everyone::addAll);
        Map<UUID, UserSummary> users = userService.summaries(everyone);

        List<UUID> toDelete = new ArrayList<>();
        for (Map.Entry<UUID, Set<UUID>> c : conversations.entrySet()) {
            boolean onlySystemOrGuests = c.getValue().stream()
                    .filter(m -> !m.equals(userId))
                    .allMatch(m -> isSystemOrGuest(users.get(m)));
            if (onlySystemOrGuests) {
                toDelete.add(c.getKey());
            }
        }
        messageService.deleteAllInConversations(toDelete);
        conversationService.deleteConversations(toDelete);
        conversationService.leaveAll(userId);
        userService.anonymize(userId, displayName);
        tokenService.revokeAllForUser(userId);
        wsTicketService.revokeAllForUser(userId);
        events.publishEvent(new UserSessionsEndedEvent(userId, reason));
    }

    private static boolean isSystemOrGuest(UserSummary u) {
        return u == null || u.isBot() || u.isGuest() || DemoSeeder.USERS.contains(u.username());
    }
}
