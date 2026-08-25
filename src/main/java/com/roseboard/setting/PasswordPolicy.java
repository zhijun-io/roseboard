package com.roseboard.setting;

import jakarta.validation.constraints.Min;

public class PasswordPolicy {
    @Min(1)
    private Integer minimumLength = 6;
    @Min(1)
    private Integer maximumLength = 72;
    @Min(0)
    private Integer minimumUppercaseLetters = 0;
    @Min(0)
    private Integer minimumLowercaseLetters = 0;
    @Min(0)
    private Integer minimumDigits = 0;
    @Min(0)
    private Integer minimumSpecialCharacters = 0;
    private Boolean allowWhitespaces = true;
    private Boolean forceUserToResetPasswordIfNotValid = false;
    @Min(0)
    private Integer passwordExpirationPeriodDays;
    @Min(0)
    private Integer passwordReuseFrequencyDays;

    public Integer getMinimumLength() { return minimumLength; }
    public void setMinimumLength(Integer value) { minimumLength = value; }
    public Integer getMaximumLength() { return maximumLength; }
    public void setMaximumLength(Integer value) { maximumLength = value; }
    public Integer getMinimumUppercaseLetters() { return minimumUppercaseLetters; }
    public void setMinimumUppercaseLetters(Integer value) { minimumUppercaseLetters = value; }
    public Integer getMinimumLowercaseLetters() { return minimumLowercaseLetters; }
    public void setMinimumLowercaseLetters(Integer value) { minimumLowercaseLetters = value; }
    public Integer getMinimumDigits() { return minimumDigits; }
    public void setMinimumDigits(Integer value) { minimumDigits = value; }
    public Integer getMinimumSpecialCharacters() { return minimumSpecialCharacters; }
    public void setMinimumSpecialCharacters(Integer value) { minimumSpecialCharacters = value; }
    public Boolean getAllowWhitespaces() { return allowWhitespaces; }
    public void setAllowWhitespaces(Boolean value) { allowWhitespaces = value; }
    public Boolean getForceUserToResetPasswordIfNotValid() { return forceUserToResetPasswordIfNotValid; }
    public void setForceUserToResetPasswordIfNotValid(Boolean value) { forceUserToResetPasswordIfNotValid = value; }
    public Integer getPasswordExpirationPeriodDays() { return passwordExpirationPeriodDays; }
    public void setPasswordExpirationPeriodDays(Integer value) { passwordExpirationPeriodDays = value; }
    public Integer getPasswordReuseFrequencyDays() { return passwordReuseFrequencyDays; }
    public void setPasswordReuseFrequencyDays(Integer value) { passwordReuseFrequencyDays = value; }
}
