package com.roseboard.setting;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PasswordPolicyService {
    private final AdminSettingService adminSettingService;

    public PasswordPolicyService(AdminSettingService adminSettingService) {
        this.adminSettingService = adminSettingService;
    }

    public void validate(String password) {
        PasswordPolicy policy = adminSettingService.getSecuritySettings().getPasswordPolicy();
        if (password == null) {
            throw invalid("Password is required");
        }
        if (policy.getMinimumLength() != null && password.length() < policy.getMinimumLength()) {
            throw invalid("Password is shorter than the minimum length");
        }
        if (policy.getMaximumLength() != null && password.length() > policy.getMaximumLength()) {
            throw invalid("Password exceeds the maximum length");
        }
        if (policy.getMinimumUppercaseLetters() != null
                && count(password, Character::isUpperCase) < policy.getMinimumUppercaseLetters()) {
            throw invalid("Password does not contain enough uppercase letters");
        }
        if (policy.getMinimumLowercaseLetters() != null
                && count(password, Character::isLowerCase) < policy.getMinimumLowercaseLetters()) {
            throw invalid("Password does not contain enough lowercase letters");
        }
        if (policy.getMinimumDigits() != null
                && count(password, Character::isDigit) < policy.getMinimumDigits()) {
            throw invalid("Password does not contain enough digits");
        }
        if (policy.getMinimumSpecialCharacters() != null
                && count(password, character -> !Character.isLetterOrDigit(character)
                && !Character.isWhitespace(character)) < policy.getMinimumSpecialCharacters()) {
            throw invalid("Password does not contain enough special characters");
        }
        if (Boolean.FALSE.equals(policy.getAllowWhitespaces()) && password.chars().anyMatch(Character::isWhitespace)) {
            throw invalid("Password must not contain whitespace");
        }
    }

    private static long count(String value, java.util.function.IntPredicate predicate) {
        return value.chars().filter(predicate).count();
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
