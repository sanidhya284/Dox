package com.chatplatform.guest;

import com.chatplatform.auth.dto.Registration;
import com.chatplatform.auth.service.TokenService;
import com.chatplatform.conversation.dto.ConversationResponse;
import com.chatplatform.conversation.dto.CreateDirectRequest;
import com.chatplatform.conversation.dto.CreateGroupRequest;
import com.chatplatform.conversation.service.ConversationService;
import com.chatplatform.message.service.MessageService;
import com.chatplatform.shared.exception.ApiException;
import com.chatplatform.shared.exception.ErrorCode;
import com.chatplatform.user.dto.UserResponse;
import com.chatplatform.user.repository.User;
import com.chatplatform.user.service.UserService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * "Try the demo" (ADR-0012): one click creates a guest account, a welcome DM from the bot and a private group
 * with the bot, then logs the guest in like any user (refresh cookie). Guests never join shared conversations,
 * so other users' lists are untouched. At most {@code guest.max-live} guests exist at once (DEMO_BUSY beyond;
 * the count is checked, not locked, so concurrent requests can overshoot by a few).
 */
@Service
public class GuestService {

    private final UserService userService;
    private final ConversationService conversationService;
    private final MessageService messageService;
    private final TokenService tokenService;
    private final WelcomeBot bot;
    private final int maxLive;

    public GuestService(UserService userService, ConversationService conversationService,
                        MessageService messageService, TokenService tokenService, WelcomeBot bot,
                        MeterRegistry meters, @Value("${guest.max-live:1000}") int maxLive) {
        this.userService = userService;
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.tokenService = tokenService;
        this.bot = bot;
        this.maxLive = maxLive;
        Gauge.builder("chat.guests.live", userService, UserService::countLiveGuests)
                .description("Guest accounts that exist now (not yet removed)").register(meters);
    }

    @Transactional
    public Registration create() {
        if (userService.countLiveGuests() >= maxLive) {
            throw new ApiException(ErrorCode.DEMO_BUSY);
        }
        UUID botId = bot.id();
        User guest = userService.createGuest();

        ConversationResponse dm = conversationService.createDirect(guest.id(), new CreateDirectRequest(botId));
        messageService.sendAsSystem(dm.id(), botId, WelcomeBot.WELCOME);
        ConversationResponse group = conversationService.createGroup(botId,
                new CreateGroupRequest(WelcomeBot.GROUP_TITLE, List.of(guest.id())));
        messageService.sendAsSystem(group.id(), botId, WelcomeBot.GROUP_WELCOME);

        return new Registration(UserResponse.from(guest), tokenService.generateTokenPair(guest.id()));
    }
}
