package me.legrange.mikrotik.impl;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class RawSentenceTest {

    @Test
    public void textResultPreservesEmbeddedLineBreaksAndTag() throws Exception {
        RawSentence sentence = new RawSentence(Arrays.asList(
                text("!re"), text("=name=line1\r\nline2"), text(".tag=7")));

        Result result = (Result) sentence.toTextResponse();

        assertEquals("!re", sentence.getType());
        assertEquals("7", sentence.getTag());
        assertEquals("7", result.getTag());
        assertEquals("line1\r\nline2", result.get("name"));
    }

    @Test
    public void donePreservesRetAndTag() throws Exception {
        RawSentence sentence = new RawSentence(Arrays.asList(
                text("!done"), text("=ret=abc123"), text(".tag=8")));

        Done done = (Done) sentence.toTextResponse();

        assertEquals("8", done.getTag());
        assertEquals("abc123", done.getHash());
    }

    @Test
    public void trapPreservesMessageCategoryAndTag() throws Exception {
        RawSentence sentence = new RawSentence(Arrays.asList(
                text("!trap"), text("=message=bad command"), text("=category=5"), text(".tag=9")));

        Error error = (Error) sentence.toTextResponse();

        assertEquals("9", error.getTag());
        assertEquals("bad command", error.getMessage());
        assertEquals(5, error.getCategory());
    }

    @Test
    public void rawDataAttributePreservesHostileBinaryBytes() throws Exception {
        byte[] payload = new byte[]{
            0, 13, 10, (byte) 0xc0, (byte) 0xaf, (byte) 0xff, (byte) 0xfe,
            '=', 'd', 'a', 't', 'a', '=', '!', 'r', 'e', '!', 'd', 'o', 'n', 'e'
        };
        byte[] word = new byte[6 + payload.length];
        System.arraycopy(text("=data="), 0, word, 0, 6);
        System.arraycopy(payload, 0, word, 6, payload.length);
        RawSentence sentence = new RawSentence(Arrays.asList(text("!re"), word, text(".tag=a")));

        assertArrayEquals(payload, sentence.requireSingleRawAttribute("data"));
    }

    @Test
    public void rawDataAttributeMustExistExactlyOnce() throws Exception {
        try {
            new RawSentence(Arrays.asList(text("!re"), text(".tag=x")))
                    .requireSingleRawAttribute("data");
            fail("Expected missing data to fail");
        } catch (ApiDataException expected) {
            // expected
        }

        try {
            new RawSentence(Arrays.asList(text("!re"), text("=data=a"), text("=data=b"), text(".tag=x")))
                    .requireSingleRawAttribute("data");
            fail("Expected duplicate data to fail");
        } catch (ApiDataException expected) {
            // expected
        }
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
