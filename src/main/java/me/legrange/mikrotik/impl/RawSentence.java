package me.legrange.mikrotik.impl;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import me.legrange.mikrotik.MikrotikApiException;

/**
 * One complete RouterOS API response sentence with raw word boundaries intact.
 */
final class RawSentence {

    RawSentence(List<byte[]> words) {
        this.words = words;
    }

    String getType() throws ApiDataException {
        if (words.isEmpty()) {
            throw new ApiDataException("Empty RouterOS response sentence");
        }
        return text(words.get(0));
    }

    String getTag() throws ApiDataException {
        String tag = null;
        byte[] prefix = ".tag=".getBytes(StandardCharsets.US_ASCII);
        for (byte[] word : words) {
            if (startsWith(word, prefix)) {
                if (tag != null) {
                    throw new ApiDataException("RouterOS sentence contains multiple tags");
                }
                tag = new String(word, prefix.length, word.length - prefix.length, StandardCharsets.UTF_8);
            }
        }
        return tag;
    }

    Response toTextResponse() throws MikrotikApiException {
        switch (getType()) {
            case "!re":
                return toResult();
            case "!done":
                return toDone();
            case "!trap":
            case "!halt":
                return toError();
            default:
                throw new ApiDataException(String.format("Unexpected response type '%s'", getType()));
        }
    }

    byte[] requireSingleRawAttribute(String name) throws ApiDataException {
        byte[] prefix = ("=" + name + "=").getBytes(StandardCharsets.US_ASCII);
        byte[] value = null;
        for (byte[] word : words) {
            if (startsWith(word, prefix)) {
                if (value != null) {
                    throw new ApiDataException(String.format("RouterOS sentence contains multiple '%s' attributes", name));
                }
                value = Arrays.copyOfRange(word, prefix.length, word.length);
            }
        }
        if (value == null) {
            throw new ApiDataException(String.format("RouterOS sentence contains no '%s' attribute", name));
        }
        return value;
    }

    private Result toResult() throws ApiDataException {
        Result result = new Result();
        result.setTag(getTag());
        for (int i = 1; i < words.size(); i++) {
            byte[] word = words.get(i);
            if (isTag(word)) {
                continue;
            }
            Attribute attribute = textAttribute(word);
            result.put(attribute.name, attribute.value);
        }
        return result;
    }

    private Done toDone() throws ApiDataException {
        Done done = new Done(getTag());
        for (int i = 1; i < words.size(); i++) {
            byte[] word = words.get(i);
            if (isTag(word)) {
                continue;
            }
            Attribute attribute = textAttribute(word);
            if ("ret".equals(attribute.name)) {
                done.setHash(attribute.value);
            }
        }
        return done;
    }

    private Error toError() throws ApiDataException {
        Error error = new Error();
        error.setTag(getTag());
        for (int i = 1; i < words.size(); i++) {
            byte[] word = words.get(i);
            if (isTag(word)) {
                continue;
            }
            Attribute attribute = textAttribute(word);
            if ("message".equals(attribute.name)) {
                error.setMessage(attribute.value);
            } else if ("category".equals(attribute.name)) {
                try {
                    error.setCategory(Integer.parseInt(attribute.value));
                } catch (NumberFormatException ex) {
                    throw new ApiDataException("Invalid RouterOS error category", ex);
                }
            }
        }
        return error;
    }

    private static Attribute textAttribute(byte[] word) throws ApiDataException {
        if (word.length < 3 || word[0] != '=') {
            throw new ApiDataException(String.format("Unexpected RouterOS response word '%s'", text(word)));
        }
        int separator = -1;
        for (int i = 1; i < word.length; i++) {
            if (word[i] == '=') {
                separator = i;
                break;
            }
        }
        if (separator < 0) {
            throw new ApiDataException(String.format("Malformed RouterOS attribute '%s'", text(word)));
        }
        String name = new String(word, 1, separator - 1, StandardCharsets.UTF_8);
        String value = new String(word, separator + 1, word.length - separator - 1, StandardCharsets.UTF_8);
        return new Attribute(name, value);
    }

    private static boolean isTag(byte[] word) {
        return startsWith(word, ".tag=".getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (value[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    private static final class Attribute {
        private final String name;
        private final String value;

        private Attribute(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

    private final List<byte[]> words;
}
