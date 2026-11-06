package com.veneer.hooks;

import com.veneer.VeneerPlugin;
import com.veneer.core.db.PlayerStore.AuthMeAccount;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Copies what AuthMe knows about registered accounts for the alt graph: registration and last addresses, also of
 * accounts not seen since Veneer was installed, and a hash of the e-mail, so the address itself is never stored here.
 * Through reflection: AuthMe publishes no artifact in a repository this build uses, and a missing method must only
 * turn the link off.
 */
final class AuthMeHook {

    private static final String DEFAULT_EMAIL = "your@email.com";

    private final VeneerPlugin plugin;
    private final Object api;
    private final Method playerInfo, names;
    private final Method name, uuid, email, registered, regIp, lastLogin, lastIp;

    AuthMeHook(VeneerPlugin plugin) throws ReflectiveOperationException {
        this.plugin = plugin;
        ClassLoader loader = plugin.getServer().getPluginManager().getPlugin("AuthMe").getClass().getClassLoader();
        Class<?> apiClass = Class.forName("fr.xephi.authme.api.v3.AuthMeApi", true, loader);
        Class<?> player = Class.forName("fr.xephi.authme.api.v3.AuthMePlayer", true, loader);
        api = apiClass.getMethod("getInstance").invoke(null);
        if (api == null) throw new IllegalStateException("AuthMe API is not ready");
        playerInfo = apiClass.getMethod("getPlayerInfo", String.class);
        names = apiClass.getMethod("getRegisteredRealNames");
        name = player.getMethod("getName");
        uuid = player.getMethod("getUuid");
        email = player.getMethod("getEmail");
        registered = player.getMethod("getRegistrationDate");
        regIp = player.getMethod("getRegistrationIpAddress");
        lastLogin = player.getMethod("getLastLoginDate");
        lastIp = player.getMethod("getLastLoginIpAddress");
    }

    /** Replaces the stored copy; AuthMe reads its database per account, so never call this on a game thread. */
    int sync(int retentionDays) throws ReflectiveOperationException {
        var players = plugin.database().players();
        Map<String, String> known = players.accountUuids();
        long oldest = retentionDays > 0 ? System.currentTimeMillis() - retentionDays * 86_400_000L : 0;
        boolean online = plugin.getServer().getOnlineMode();
        List<AuthMeAccount> rows = new ArrayList<>();
        for (Object listed : (List<?>) names.invoke(api)) {
            Optional<?> info = (Optional<?>) playerInfo.invoke(api, listed);
            if (info.isEmpty()) continue;
            Object p = info.get();
            String accountName = (String) name.invoke(p);
            String id = known.get(accountName.toLowerCase(Locale.ROOT));
            if (id == null) id = ((Optional<?>) uuid.invoke(p)).map(Object::toString).orElse(null);
            // An offline-mode server derives the id from the name; on an online one an unknown account has no id
            if (id == null && !online)
                id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + accountName).getBytes(StandardCharsets.UTF_8)).toString();
            if (id == null) continue;
            Instant reg = (Instant) registered.invoke(p);
            long last = ((Optional<?>) lastLogin.invoke(p)).map(t -> ((Instant) t).toEpochMilli())
                    .orElse(reg != null ? reg.toEpochMilli() : 0L);
            if (last < oldest) continue;
            rows.add(new AuthMeAccount(id, accountName, address((Optional<?>) regIp.invoke(p)),
                    address((Optional<?>) lastIp.invoke(p)), last, emailHash((Optional<?>) email.invoke(p))));
        }
        players.replaceAuthMeAccounts(rows);
        return rows.size();
    }

    /** AuthMe writes the loopback address when it has no real one, and that would link everybody. */
    static String address(Optional<?> value) {
        String ip = value.map(Object::toString).map(String::strip).orElse("");
        boolean local = ip.isEmpty() || ip.startsWith("127.") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1")
                || ip.equalsIgnoreCase("localhost");
        return local ? null : ip;
    }

    static String emailHash(Optional<?> value) {
        String mail = value.map(Object::toString).map(s -> s.strip().toLowerCase(Locale.ROOT)).orElse("");
        if (mail.isEmpty() || mail.equals(DEFAULT_EMAIL)) return null;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mail.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
