package com.chatplatform.user.service;

import com.chatplatform.shared.exception.ApiException;
import com.chatplatform.shared.exception.ErrorCode;
import com.chatplatform.user.repository.User;
import com.chatplatform.user.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User getUserById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
    }

    // Methods for registration are handled by AuthService calling UserRepository directly,
    // or AuthService could call UserService. Since they are closely related and
    // UserRepository provides the precise primitives, AuthService can use UserRepository.
}
