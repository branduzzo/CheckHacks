package com.veneer.core;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/** Downloads a player's skin texture and returns it as a base64 PNG, so the panel can draw the head without an outside image host. */
public final class Heads {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private Heads() {}

    /** Only Mojang's texture host is fetched: the skin URL comes from the player's profile, which a client can influence offline. */
    public static boolean isTextureUrl(String url) {
        try {
            URI u = URI.create(url);
            return "textures.minecraft.net".equals(u.getHost()) && ("http".equals(u.getScheme()) || "https".equals(u.getScheme()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Downloads the skin and returns it as a base64 PNG, or null when it cannot be read. */
    public static String fetch(String skinUrl) {
        if (skinUrl == null || !isTextureUrl(skinUrl)) return null;
        try {
            HttpResponse<byte[]> res = HTTP.send(HttpRequest.newBuilder(URI.create(skinUrl))
                    .timeout(Duration.ofSeconds(8)).build(), HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200 || res.body().length > 1_000_000) return null;
            return render(ImageIO.read(new ByteArrayInputStream(res.body())));
        } catch (Exception e) {
            return null;
        }
    }

    /** The face and hat layer of a stored 64x64 skin as a 32x32 PNG (nearest neighbour), or null when it cannot be read. */
    public static byte[] face(String skinPngBase64) {
        try {
            BufferedImage skin = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(skinPngBase64)));
            if (skin == null || skin.getWidth() != 64 || skin.getHeight() != 64) return null;
            BufferedImage out = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 32; y++)
                for (int x = 0; x < 32; x++) {
                    int base = skin.getRGB(8 + x / 4, 8 + y / 4);
                    int hat = skin.getRGB(40 + x / 4, 8 + y / 4);
                    out.setRGB(x, y, (hat >>> 24) != 0 ? hat : base);
                }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(out, "png", bytes);
            return bytes.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** Re-encodes the skin as a 64x64 PNG (legacy 64x32 skins and HD skins are brought to that size). */
    static String render(BufferedImage skin) throws java.io.IOException {
        if (skin == null || skin.getWidth() < 64 || skin.getWidth() % 64 != 0 || skin.getHeight() < skin.getWidth() / 2) return null;
        int px = skin.getWidth() / 64;
        BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64 && y * px < skin.getHeight(); y++)
            for (int x = 0; x < 64; x++)
                out.setRGB(x, y, skin.getRGB(x * px, y * px));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(out, "png", bytes);
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
}
