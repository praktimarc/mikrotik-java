package me.legrange.mikrotik;

/**
 * Thrown when RouterOS reports a command-level API error.
 */
public class ApiCommandException extends MikrotikApiException {

    private final String tag;
    private final Integer category;

    protected ApiCommandException(String message, String tag, Integer category) {
        super(message);
        this.tag = tag;
        this.category = category;
    }

    protected ApiCommandException(String message, String tag, Integer category, Throwable cause) {
        super(message, cause);
        this.tag = tag;
        this.category = category;
    }

    public String getTag() {
        return tag;
    }

    public int getCategory() {
        return category == null ? 0 : category;
    }

    public boolean hasCategory() {
        return category != null;
    }
}
