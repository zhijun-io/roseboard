package com.roseboard.user;

import java.util.UUID;

public record UserEmailInfo(UUID id, String email, String firstName, String lastName) {
}
