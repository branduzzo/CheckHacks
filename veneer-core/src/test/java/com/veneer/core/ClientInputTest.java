package com.veneer.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ClientInputTest {

    private static byte[] brand(String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        int length = body.length, at = 0;
        byte[] out = new byte[body.length + 5];
        while (length >= 0x80) { out[at++] = (byte) (length | 0x80); length >>>= 7; }
        out[at++] = (byte) length;
        System.arraycopy(body, 0, out, at, body.length);
        return java.util.Arrays.copyOf(out, at + body.length);
    }

    @Test
    void decodesBrands() {
        assertEquals("fabric", Text.decodeBrand(brand("fabric")));
        assertEquals("брэнд", Text.decodeBrand(brand("брэнд")));
        String longName = "x".repeat(200);   // a two-byte length must not leave a stray character in front
        assertEquals(longName, Text.decodeBrand(brand(longName)));
        assertEquals("ab", Text.decodeBrand(brand("a\n\u0000b")));
        assertEquals(256, Text.decodeBrand(brand("y".repeat(1000))).length());
    }

    @Test
    void rejectsMalformedBrands() {
        assertNull(Text.decodeBrand(new byte[0]));
        assertNull(Text.decodeBrand(new byte[]{0}));
        assertNull(Text.decodeBrand(new byte[]{5, 'a'}));
        assertNull(Text.decodeBrand(new byte[]{-1, -1, -1, -1, -1, -1}));
        assertNull(Text.decodeBrand(new byte[]{-1, -1, -1, -1, 15, 'a'}));
    }

    @Test
    void capsChannels() {
        PlayerProfile profile = new PlayerProfile();
        assertTrue(profile.addChannel("a:b"));
        assertFalse(profile.addChannel("a:b"));
        assertFalse(profile.addChannel("c".repeat(PlayerProfile.MAX_CHANNEL_LENGTH + 1)));
        for (int i = 1; i < PlayerProfile.MAX_CHANNELS; i++) assertTrue(profile.addChannel("m:" + i));
        assertFalse(profile.addChannel("m:over"));
        assertEquals(PlayerProfile.MAX_CHANNELS, profile.getChannels().size());
    }

    @Test
    void verdictsStayUnknownUntilTheCheckRan() {
        PlayerProfile profile = new PlayerProfile();
        assertEquals(Verdict.UNKNOWN, profile.exploitPreventerVerdict());
        assertEquals(Verdict.UNKNOWN, profile.languageVerdict());
        assertEquals(Verdict.UNKNOWN, profile.protocolVerdict());

        profile.markSignAnswered();
        assertEquals(Verdict.NOT_DETECTED, profile.exploitPreventerVerdict());
        profile.setExploitPreventer(true);
        assertEquals(Verdict.DETECTED, profile.exploitPreventerVerdict());

        profile.setLanguageProbe(null);
        assertEquals(Verdict.NOT_DETECTED, profile.languageVerdict());
        profile.setLanguageProbe("ru_ru");
        assertEquals(Verdict.DETECTED, profile.languageVerdict());

        profile.setVersionProbe("1.21", "1.21", false);
        assertEquals(Verdict.NOT_DETECTED, profile.protocolVerdict());
        profile.setVersionProbe("1.21", "1.20", true);
        assertEquals(Verdict.DETECTED, profile.protocolVerdict());
    }
}
