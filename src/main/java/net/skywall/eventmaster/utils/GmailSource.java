package net.skywall.eventmaster.utils;

import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import net.skywall.eventmaster.model.EmailMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Reads Gmail messages from a label via IMAPS using Jakarta Mail. Gmail exposes
 * each user-created label as an IMAP folder of the same name.
 *
 * <p>Returns a list of {@link EmailMessage} DTOs so callers don't have to touch
 * Jakarta Mail types. Mirrors the IMAP loop body of {@code main()} in
 * {@code fetch_events.py}.
 */
public final class GmailSource {

    private static final String HOST = "imap.gmail.com";
    private static final Logger log = LoggerFactory.getLogger(GmailSource.class);

    private GmailSource() {}

    public static List<EmailMessage> readLabel(String user, String appPassword, String label) throws Exception {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", HOST);
        props.put("mail.imaps.port", "993");
        props.put("mail.imaps.ssl.enable", "true");
        props.put("mail.mime.charset", "UTF-8");

        Session session = Session.getInstance(props);
        List<EmailMessage> out = new ArrayList<>();

        try (Store store = session.getStore("imaps")) {
            store.connect(HOST, user, appPassword);
            Folder folder = store.getFolder(label);
            if (!folder.exists()) {
                throw new IllegalStateException("Gmail label not found: " + label);
            }
            folder.open(Folder.READ_ONLY);
            log.info("Connected to Gmail, reading label '{}'", label);

            try {
                for (Message msg : folder.getMessages()) {
                    out.add(toDto(msg));
                }
            } finally {
                folder.close(false);
            }
        }

        return out;
    }

    private static EmailMessage toDto(Message msg) throws Exception {
        String messageId = firstHeader(msg, "Message-ID");
        String from = (msg.getFrom() != null && msg.getFrom().length > 0)
                ? msg.getFrom()[0].toString() : "";
        String subject = msg.getSubject() != null ? msg.getSubject() : "";

        StringBuilder text = new StringBuilder();
        List<EmailMessage.Attachment> attachments = new ArrayList<>();
        collectParts(msg, text, attachments);

        if (messageId == null || messageId.isBlank()) {
            messageId = from + "|" + subject + "|" + (msg.getSentDate() != null ? msg.getSentDate() : "");
        }

        return new EmailMessage(messageId, from, subject, text.toString(), attachments);
    }

    /**
     * Walk a {@link Part} tree, collecting all {@code text/plain} content into
     * {@code textOut} and any non-inline parts with filenames into
     * {@code attachments}.
     */
    private static void collectParts(
            Part part,
            StringBuilder textOut,
            List<EmailMessage.Attachment> attachments
    ) throws Exception {
        if (part.isMimeType("text/plain") && part.getFileName() == null) {
            Object content = part.getContent();
            if (content instanceof String s) {
                if (!textOut.isEmpty()) {
                    textOut.append('\n');
                }
                textOut.append(s);
            }
            return;
        }

        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                collectParts(mp.getBodyPart(i), textOut, attachments);
            }
            return;
        }

        String filename = part.getFileName();
        if (filename != null) {
            try (InputStream in = part.getInputStream()) {
                attachments.add(new EmailMessage.Attachment(filename, in.readAllBytes()));
            }
        }
    }

    private static String firstHeader(Message msg, String name) throws Exception {
        String[] values = msg.getHeader(name);
        return (values != null && values.length > 0) ? values[0] : null;
    }
}
