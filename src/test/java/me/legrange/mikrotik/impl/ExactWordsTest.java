package me.legrange.mikrotik.impl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ExactWordsTest {
    @Test
    public void preservesUnparsedQueryVariantsAndWordOrder() throws Exception {
        List<String> words = Arrays.asList(
                "/interface/wifi/capsman/remote-cap/print",
                "?=base-mac=D0:EA:11:8A:2F:9B",
                "?base-mac",
                "=comment=spaces and quotes \"inside\"");
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        Util.writeWords(words, "debug-tag", wire);
        List<byte[]> sentence = Util.readSentence(new ByteArrayInputStream(wire.toByteArray()));
        assertEquals(words.size() + 1, sentence.size());
        for (int i = 0; i < words.size(); i++) {
            assertEquals(words.get(i), new String(sentence.get(i), StandardCharsets.UTF_8));
        }
        assertEquals(".tag=debug-tag",
                new String(sentence.get(words.size()), StandardCharsets.UTF_8));
    }
}
