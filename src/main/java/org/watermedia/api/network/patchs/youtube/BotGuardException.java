package org.watermedia.api.network.patchs.youtube;

public class BotGuardException extends Exception {
    public BotGuardException(String message) {
        super(message);
    }

    public BotGuardException(String message, Throwable cause) {
        super(message, cause);
    }
}
