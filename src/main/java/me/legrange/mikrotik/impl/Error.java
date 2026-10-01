package me.legrange.mikrotik.impl;

/**
 * Used to encapsulate API error information. We need to pass both the message and the tag (if one was used).
 *
 * @author GideonLeGrange
 */
class Error extends Response {

    private String message;
    private int category;
    private boolean categoryPresent;

    Error(String tag, String message, int category) {
        super(tag);
        this.message = message;
        this.category = category;
        this.categoryPresent = true;
    }

    Error() {
        super(null);
    }

    String getMessage() {
        return message;
    }

    void setMessage(String message) {
        this.message = message;
    }

    int getCategory() {
        return category;
    }

    boolean hasCategory() {
        return categoryPresent;
    }

    void setCategory(int category) {
        this.category = category;
        this.categoryPresent = true;
    }
}
