package com.microlearning.todo.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * NotificationService — simulated notification domain.
 *
 * ARCHITECTURE NOTE — WHY THIS IS ITS OWN MODULE:
 * Even in a monolith, notification logic should be isolated in its own
 * module. It has a clear, separate responsibility and will eventually
 * evolve differently from User or Todo:
 * - Notifications may need different scaling (email/SMS burst traffic)
 * - Notifications can be async — users don't wait for emails to be sent
 * - Notification providers change (SendGrid → SES → Twilio)
 *
 * In Phase 4, we will:
 * 1. Remove this direct injection from UserService
 * 2. Have UserService publish a "UserCreated" event to Kafka
 * 3. Extract this into a standalone Notification Service that
 *    CONSUMES the event asynchronously
 *
 * IMPORTANT INTERVIEW POINT:
 * Right now, if this sendWelcomeEmail() method throws an exception,
 * the entire user creation transaction ROLLS BACK.
 * A failed email cancels a user registration — is that correct behaviour?
 * Almost certainly not. This is why notifications should be async.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /**
     * Simulates sending a welcome email.
     * In production this would use JavaMailSender, SendGrid, AWS SES, etc.
     *
     * NOTE: This is SYNCHRONOUS — the HTTP request thread blocks here
     * waiting for the email to "send". If the email provider is slow
     * or down, this blocks the createUser() call and eventually
     * times out the HTTP request.
     */
    public void sendWelcomeEmail(String email, String name) {
        log.info("[NOTIFICATION] Sending welcome email to {} <{}>", name, email);
    }

    public void sendTodoCompletionEmail(String email, String todoTitle) {
        log.info("[NOTIFICATION] Sending todo-completion email to {} for todo: '{}'", email, todoTitle);
    }
}
