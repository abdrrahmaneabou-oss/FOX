package com.ponie.dayov12;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Strict single-peer parser. Never silently discards an AWG parameter. */
public final class FoxAwgConfig {
    public final Map<String,String> iface = new LinkedHashMap<>();
    public final Map<String,String> peer = new LinkedHashMap<>();
    private static final Map<String,String> IF_UAPI = new LinkedHashMap<>();
    static {
        String[][] fields = {
            {"ListenPort","listen_port"}, {"Jc","jc"}, {"Jmin","jmin"}, {"Jmax","jmax"},
            {"S1","s1"}, {"S2","s2"}, {"S3","s3"}, {"S4","s4"},
            {"H1","h1"}, {"H2","h2"}, {"H3","h3"}, {"H4","h4"},
            {"I1","i1"}, {"I2","i2"}, {"I3","i3"}, {"I4","i4"}, {"I5","i5"},
            {"HeaderProtectionKey","header_protection_key"},
            {"ContentPaddingAddition","content_padding_addition"},
            {"RekeyAfterTime","rekey_after_time"}, {"RekeyTimeout","rekey_timeout"},
            {"RejectAfterTime","reject_after_time"}, {"KeepaliveTimeout","keepalive_timeout"},
            {"MaxHandshakeAttempts","max_handshake_attempts"},
            {"RandomTrailers","random_trailers"}, {"DisableCookies","disable_cookies"}
        };
        for (String[] pair : fields) IF_UAPI.put(pair[0], pair[1]);
    }
    public static byte[] readLimited(InputStream in) throws IOException {
        return readLimited(in,32768);
    }
    public static byte[] readLimited(InputStream in,int maximum) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[2048];
        int n;
        while ((n = in.read(b)) != -1) {
            if (out.size() + n > maximum) throw new IOException("CONFIG_TOO_LARGE");
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }
    public static FoxAwgConfig parse(byte[] bytes) throws IOException {
        if (bytes.length > 32768) throw new IOException("CONFIG_TOO_LARGE");
        FoxAwgConfig c = new FoxAwgConfig();
        Map<String,String> section = null;
        boolean haveIface = false, havePeer = false;
        BufferedReader r = new BufferedReader(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
        String line;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
            if (line.equals("[Interface]")) {
                if (haveIface || havePeer) throw new IOException("DUPLICATE_INTERFACE");
                haveIface = true; section = c.iface; continue;
            }
            if (line.equals("[Peer]")) {
                if (!haveIface || havePeer) throw new IOException("ONE_PEER_REQUIRED");
                havePeer = true; section = c.peer; continue;
            }
            int eq = line.indexOf('=');
            if (section == null || eq <= 0) throw new IOException("INVALID_CONFIG_LINE");
            String key = line.substring(0, eq).trim(), value = line.substring(eq+1).trim();
            if (!key.matches("[A-Za-z][A-Za-z0-9]*")) throw new IOException("INVALID_FIELD_NAME");
            boolean known = section == c.iface
                ? IF_UAPI.containsKey(key) || Arrays.asList("PrivateKey","Address","DNS","MTU").contains(key)
                : Arrays.asList("PublicKey","PresharedKey","Endpoint","AllowedIPs","PersistentKeepalive").contains(key);
            if (!known) throw new IOException("UNSUPPORTED_FIELD:" + key);
            if (value.isEmpty() || section.put(key, value) != null) throw new IOException("INVALID_OR_DUPLICATE_FIELD:" + key);
        }
        if (!haveIface || !havePeer) throw new IOException("INTERFACE_AND_PEER_REQUIRED");
        c.required(c.iface, "PrivateKey"); c.required(c.iface, "Address");
        c.required(c.peer, "PublicKey"); c.required(c.peer, "Endpoint"); c.required(c.peer, "AllowedIPs");
        hexKey(c.iface.get("PrivateKey")); hexKey(c.peer.get("PublicKey"));
        if (c.iface.containsKey("HeaderProtectionKey")) hexKey(c.iface.get("HeaderProtectionKey"));
        if (c.peer.containsKey("PresharedKey")) hexKey(c.peer.get("PresharedKey"));
        for (String ip : c.addresses()) validateCidr(ip);
        for (String ip : c.allowedIps()) validateCidr(ip);
        for (String ip : c.dns()) numericAddress(ip);
        c.mtu();
        return c;
    }
    private void required(Map<String,String> m, String name) throws IOException {
        if (!m.containsKey(name)) throw new IOException("MISSING_FIELD:" + name);
    }
    private static String[] list(String s) { return s == null ? new String[0] : s.split("\\s*,\\s*"); }
    public String[] addresses() { return list(iface.get("Address")); }
    public String[] allowedIps() { return list(peer.get("AllowedIPs")); }
    public String[] dns() { return list(iface.get("DNS")); }
    public int mtu() throws IOException {
        try {
            int mtu = Integer.parseInt(iface.getOrDefault("MTU", "1280"));
            if (mtu < 1280 || mtu > 1500) throw new NumberFormatException();
            return mtu;
        } catch (NumberFormatException e) { throw new IOException("INVALID_MTU"); }
    }
    static InetAddress numericAddress(String ip) throws IOException {
        if (!ip.matches(ip.contains(":") ? "[0-9a-fA-F:.]+" : "[0-9]+(?:\\.[0-9]+){3}")) throw new IOException("NUMERIC_ADDRESS_REQUIRED");
        try { return InetAddress.getByName(ip); } catch (Exception e) { throw new IOException("INVALID_ADDRESS"); }
    }
    private static void validateCidr(String value) throws IOException {
        try {
            String[] bits = value.split("/", -1);
            if (bits.length != 2) throw new IOException("CIDR_REQUIRED");
            int len = numericAddress(bits[0]).getAddress().length * 8;
            int p = Integer.parseInt(bits[1]);
            if (p < 0 || p > len) throw new IOException("INVALID_PREFIX");
        } catch (NumberFormatException e) { throw new IOException("INVALID_PREFIX"); }
    }
    static String hexKey(String value) throws IOException {
        try {
            byte[] key = Base64.getDecoder().decode(value);
            if (key.length != 32) throw new IOException("INVALID_KEY_LENGTH");
            StringBuilder b = new StringBuilder(64);
            for (byte v : key) { b.append(Character.forDigit((v & 255) >>> 4,16)); b.append(Character.forDigit(v & 15,16)); }
            Arrays.fill(key, (byte)0);
            return b.toString();
        } catch (IllegalArgumentException e) { throw new IOException("INVALID_KEY_ENCODING"); }
    }
    public String uapi() throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("private_key=").append(hexKey(iface.get("PrivateKey"))).append('\n');
        for (Map.Entry<String,String> e : IF_UAPI.entrySet()) {
            String v = iface.get(e.getKey());
            if (v == null) continue;
            if (e.getKey().equals("HeaderProtectionKey")) v = hexKey(v);
            if (e.getKey().equals("RandomTrailers") || e.getKey().equals("DisableCookies")) {
                if (Arrays.asList("on","1","true","yes").contains(v.toLowerCase(Locale.ROOT))) v="1";
                else if (Arrays.asList("off","0","false","no").contains(v.toLowerCase(Locale.ROOT))) v="0";
                else throw new IOException("INVALID_BOOLEAN");
            }
            b.append(e.getValue()).append('=').append(v).append('\n');
        }
        b.append("replace_peers=true\npublic_key=").append(hexKey(peer.get("PublicKey"))).append('\n');
        b.append("replace_allowed_ips=true\n");
        for (String ip : allowedIps()) b.append("allowed_ip=").append(ip).append('\n');
        b.append("endpoint=").append(resolvedEndpoint(peer.get("Endpoint"))).append('\n');
        if (peer.containsKey("PresharedKey")) b.append("preshared_key=").append(hexKey(peer.get("PresharedKey"))).append('\n');
        if (peer.containsKey("PersistentKeepalive")) b.append("persistent_keepalive_interval=").append(peer.get("PersistentKeepalive")).append('\n');
        return b.toString();
    }
    private static String resolvedEndpoint(String ep) throws IOException {
        try {
            int colon = ep.lastIndexOf(':');
            if (colon < 1) throw new IOException("INVALID_ENDPOINT");
            int port = Integer.parseInt(ep.substring(colon+1));
            if (port < 1 || port > 65535) throw new IOException("INVALID_ENDPOINT_PORT");
            String host = ep.substring(0,colon).replace("[","").replace("]","");
            String ip = InetAddress.getByName(host).getHostAddress();
            return (ip.contains(":") ? "["+ip+"]" : ip) + ":" + port;
        } catch (Exception e) { throw new IOException("INVALID_OR_UNRESOLVED_ENDPOINT"); }
    }
    @Override public String toString() { return "FoxAwgConfig[REDACTED]"; }
}
