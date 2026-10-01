package me.legrange.mikrotik;

/**
 * Public exception type for a RouterOS command-level API error.
 *
 * <p>The exception preserves the RouterOS command tag and, when RouterOS
 * supplied one, the numeric error category. A category value of {@code 0} can
 * be a real RouterOS category, so callers that need to distinguish category
 * zero from an omitted category should use {@link #hasCategory()}.</p>
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

    /**
     * Return the RouterOS command tag associated with the error.
     *
     * @return command tag, or {@code null} if no tag was available
     */
    public String getTag() {
        return tag;
    }

    /**
     * Return the RouterOS error category.
     *
     * <p>For compatibility this method returns {@code 0} both for a real
     * category zero and when RouterOS omitted the category. Use
     * {@link #hasCategory()} when that distinction matters.</p>
     *
     * @return RouterOS error category, or {@code 0} when absent
     */
    public int getCategory() {
        return category == null ? 0 : category;
    }

    /**
     * Check whether RouterOS supplied an error category.
     *
     * @return {@code true} if a category was present in the RouterOS reply
     */
    public boolean hasCategory() {
        return category != null;
    }
}
