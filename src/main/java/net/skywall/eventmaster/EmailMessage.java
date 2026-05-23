package net.skywall.eventmaster;

import java.util.List;

/**
 * Lightweight, framework-free DTO carrying the message fields the parsers need.
 * Decouples {@link EmailParser} from Jakarta Mail so it can be unit-tested
 * without an IMAP server.
 */
public record EmailMessage(
        String messageId,
        String from,
        String subject,
        String text,
        List<Attachment> attachments
) {
    public record Attachment(String filename, byte[] payload) {}
}
