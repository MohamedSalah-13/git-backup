package com.gitbackup.backup;

import java.io.IOException;

/** The ZIP is encrypted and no password, or the wrong one, was given. */
public class PasswordRequiredException extends IOException {
    private final boolean wrongPassword;

    public PasswordRequiredException(boolean wrongPassword) {
        super(wrongPassword
                ? "The password does not open this backup."
                : "This backup is encrypted and needs its password.");
        this.wrongPassword = wrongPassword;
    }

    /** True when a password was tried and rejected; false when none was given. */
    public boolean wrongPassword() {
        return wrongPassword;
    }
}
