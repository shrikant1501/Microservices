package com.microlearning.notification.consumer;

/**
 * UserCreatedEvent — notification-service's LOCAL copy of the event contract.
 *
 * IMPORTANT: This is NOT imported from user-service. It is a separate class.
 * The JSON payload is deserialized into this class using field-name matching.
 *
 * WHY NOT SHARE A LIBRARY?
 * A shared "events" library couples all services to the same release cycle.
 * If you change the event schema, you must update ALL services simultaneously.
 * With separate copies, each service updates independently (tolerant reader pattern).
 *
 * TOLERANCE: If user-service adds a new field (e.g., "phone"), notification-service's
 * copy simply ignores it (Jackson ignores unknown properties by default).
 */
public class UserCreatedEvent {
    private Long userId;
    private String email;
    private String name;

    public UserCreatedEvent() {}
    public Long getUserId()  { return userId; }
    public String getEmail() { return email; }
    public String getName()  { return name; }
    public void setUserId(Long userId)  { this.userId = userId; }
    public void setEmail(String email)  { this.email = email; }
    public void setName(String name)    { this.name = name; }
}
