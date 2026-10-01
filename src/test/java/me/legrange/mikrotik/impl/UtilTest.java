package me.legrange.mikrotik.impl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class UtilTest {

    @Test
    public void readWordPreservesEveryByteValue() throws Exception {
        byte[] payload = new byte[256];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }

        assertArrayEquals(payload, Util.readWord(new ByteArrayInputStream(word(payload))));
    }

    @Test
    public void readWordRejectsTruncatedPayload() throws Exception {
        byte[] encoded = word(new byte[]{1, 2, 3, 4});
        encoded = Arrays.copyOf(encoded, encoded.length - 1);

        try {
            Util.readWord(new ByteArrayInputStream(encoded));
            fail("Expected ApiDataException");
        } catch (ApiDataException expected) {
            // expected
        }
    }

    @Test
    public void readSentenceReturnsWordsUntilTerminator() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(word("!re".getBytes("UTF-8")));
        out.write(word("=name=value".getBytes("UTF-8")));
        out.write(0);

        List<byte[]> words = Util.readSentence(new ByteArrayInputStream(out.toByteArray()));

        assertEquals(2, words.size());
        assertArrayEquals("!re".getBytes("UTF-8"), words.get(0));
        assertArrayEquals("=name=value".getBytes("UTF-8"), words.get(1));
    }

    @Test
    public void readWordHandlesProtocolLengthBoundaries() throws Exception {
        int[] lengths = {0x7f, 0x80, 0x3fff, 0x4000, 0x1ffff, 0x20000};
        for (int length : lengths) {
            byte[] payload = new byte[length];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (i * 31);
            }
            assertArrayEquals("length " + length, payload,
                    Util.readWord(new ByteArrayInputStream(word(payload))));
        }
    }

    private static byte[] word(byte[] payload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int len = payload.length;
        if (len < 0x80) {
            out.write(len);
        } else if (len < 0x4000) {
            int encoded = len | 0x8000;
            out.write(encoded >> 8);
            out.write(encoded);
        } else if (len < 0x20000) {
            int encoded = len | 0xC00000;
            out.write(encoded >> 16);
            out.write(encoded >> 8);
            out.write(encoded);
        } else if (len < 0x10000000) {
            int encoded = len | 0xE0000000;
            out.write(encoded >> 24);
            out.write(encoded >> 16);
            out.write(encoded >> 8);
            out.write(encoded);
        } else {
            out.write(0xF0);
            out.write(len >> 24);
            out.write(len >> 16);
            out.write(len >> 8);
            out.write(len);
        }
        out.write(payload);
        return out.toByteArray();
    }
}
