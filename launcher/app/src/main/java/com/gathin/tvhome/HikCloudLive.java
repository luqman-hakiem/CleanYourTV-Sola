package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Optional high-resolution still from the Hik-Connect cloud main stream.
 *
 * <p>The plain capture API is hard-capped at 352x288 by this camera. The main stream, however, is
 * H.265 960x1080 and is relayed by Hik-Connect. This class opens that relay just long enough to
 * decode one key frame, then closes it. No password, serial or IP is compiled in: everything comes
 * from the private files already managed by {@link HikCctv}. If anything fails the caller keeps the
 * lightweight snapshot from the capture API.
 */
final class HikCloudLive {
    private static final String TAG = "HikCctvLive";
    private static final String CLIENT_VERSION = "v3.10.1.20260710";
    private static final String STREAM_TOKEN_FEATURE = "deadbeef";

    private static final int MSG_STOP_REQ = 0x130;
    private static final int MSG_KEEPALIVE_REQ = 0x132;
    private static final int MSG_KEEPALIVE_RSP = 0x133;
    private static final int MSG_STREAM_INFO_REQ = 0x13B;
    private static final int RESULT_REDIRECT = 5302;

    private static final int CHANNEL_MSG = 0x00;
    private static final int CHANNEL_MEDIA = 0x01;
    private static final int CHANNEL_ECDH_MSG = 0x0A;
    private static final int CHANNEL_ECDH_MEDIA = 0x0B;
    private static final int CHANNEL_ECDH_RTP = 0x0C;
    private static final int ECDH_MEDIA_CHUNK = 1200;
    private static final int RTP_VIDEO_PAYLOAD_TYPE = 96;

    private static KeyPair deviceKeys;

    private HikCloudLive() {
    }

    static Bitmap capture(HikCctv owner) {
        try {
            String host = owner.liveApiHost();
            String session = owner.liveSessionId();
            String feature = owner.liveFeatureCode();
            String serial = owner.liveSerial();
            int channel = owner.liveChannel();
            if (host == null || session == null || session.isEmpty()
                    || serial == null || channel <= 0) return null;

            String base = "https://" + host;
            JSONObject cameras = new JSONObject(new String(owner.apiRequest(
                    base + "/v3/userdevices/v1/cameras/info?deviceSerial=" + serial,
                    "GET", null, true), StandardCharsets.UTF_8));
            String biz = streamBizUrl(cameras, channel);

            JSONObject devices = new JSONObject(new String(owner.apiRequest(
                    base + "/v3/userdevices/v1/devices/pagelist?groupId=-1&limit=50"
                            + "&offset=0&filter=VTM,TTS", "GET", null, true),
                    StandardCharsets.UTF_8));
            String vtmHost = null;
            int vtmPort = 0;
            byte[] peerKey = null;
            int keyVersion = 1;

            JSONObject vtmBySerial = devices.optJSONObject("VTM");
            JSONObject vtm = vtmBySerial == null ? null : vtmBySerial.optJSONObject(serial);
            if (vtm != null) {
                vtmHost = vtm.optString("domain", "");
                vtmPort = vtm.optInt("port", 0);
            }
            JSONObject tts = devices.optJSONObject("TTS");
            if (tts == null) tts = devices.optJSONObject("ttsInfos");
            JSONObject ttsDevice = tts == null ? null : tts.optJSONObject(serial);
            JSONObject publicKey = ttsDevice == null ? null : ttsDevice.optJSONObject("publicKey");
            if (publicKey != null && !publicKey.optString("key", "").isEmpty()) {
                peerKey = android.util.Base64.decode(publicKey.optString("key"),
                        android.util.Base64.DEFAULT);
                keyVersion = publicKey.optInt("version", 1);
            }
            if (vtmHost == null || vtmHost.isEmpty() || vtmPort <= 0) {
                JSONObject fallback = new JSONObject(new String(owner.apiRequest(
                        base + "/v3/streaming/vtm/" + serial + '/' + channel,
                        "GET", null, true), StandardCharsets.UTF_8));
                JSONObject config = fallback.optJSONObject("streamServerConfig");
                if (config == null) return null;
                vtmHost = config.optString("domain", "");
                vtmPort = config.optInt("port", 0);
            }
            if (vtmHost.isEmpty() || vtmPort <= 0) return null;

            String tokenBody = "sessionId=" + encode(session)
                    + "&featureCode=" + encode(STREAM_TOKEN_FEATURE) + "&count=2";
            JSONObject tokenAnswer = new JSONObject(new String(owner.apiRequest(
                    base + "/api/user/token/get", "POST",
                    tokenBody.getBytes(StandardCharsets.UTF_8), true), StandardCharsets.UTF_8));
            JSONArray tokens = tokenAnswer.optJSONArray("tokenArray");
            if (tokens == null || tokens.length() == 0) {
                Log.w(TAG, "no stream tokens");
                return null;
            }

            // First attempt uses the device's ECDH key; the second token is the plain fallback.
            for (int i = 0; i < 2 && i < tokens.length(); i++) {
                String token = tokens.optString(i, "");
                if (token.isEmpty()) continue;
                byte[] key = i == 0 ? peerKey : null;
                String url = streamUrl(vtmHost, vtmPort, serial, channel, token, biz, key != null);
                Grab grab = null;
                try {
                    grab = open(url, key, keyVersion);
                    Bitmap bitmap = readFrame(grab);
                    if (bitmap != null) return bitmap;
                } catch (Exception e) {
                    Log.w(TAG, "stream attempt " + i + ": " + e.getMessage());
                } finally {
                    if (grab != null) grab.close();
                }
            }
            return null;
        } catch (Exception e) {
            Log.w(TAG, "live capture failed: " + e.getMessage());
            return null;
        }
    }

    private static String streamBizUrl(JSONObject cameras, int channel) {
        JSONArray list = cameras.optJSONArray("cameraInfos");
        if (list == null) return "";
        for (int i = 0; i < list.length(); i++) {
            JSONObject camera = list.optJSONObject(i);
            if (camera != null && camera.optInt("channelNo", -1) == channel) {
                return camera.optString("streamBizUrl", "");
            }
        }
        return "";
    }

    private static String streamUrl(String host, int port, String serial, int channel,
                                    String token, String biz, boolean ecdh) {
        StringBuilder url = new StringBuilder("ysproto://").append(host).append(':').append(port)
                .append("/live?dev=").append(serial)
                .append("&chn=").append(channel)
                .append("&stream=1&cln=55&isp=0&auth=1&ssn=").append(token);
        if (biz != null && !biz.isEmpty()) url.append('&').append(biz);
        url.append("&vip=0&timestamp=").append(System.currentTimeMillis());
        if (ecdh) url.append("&ecdh=1");
        return url.toString();
    }

    private static Grab open(String url, byte[] peerKey, int keyVersion) throws Exception {
        Envelope link = peerKey == null ? null : new Envelope(deviceKey(), peerKey, keyVersion);
        Conn first = new Conn(hostOf(url), portOf(url), link);
        try {
            Received received = first.request(streamInfoRequest(url, null), MSG_STREAM_INFO_REQ);
            Map<Integer, List<Object>> answer = Pb.decode(received.body);
            long result = Pb.intValue(answer, 1);
            byte[] streamKey = Pb.bytes(answer, 5);
            byte[] redirect = Pb.bytes(answer, 7);
            if (redirect.length == 0 || (result != 0 && result != RESULT_REDIRECT)) {
                throw new IllegalStateException("VTM result " + result);
            }
            URI target = URI.create(new String(redirect, StandardCharsets.UTF_8));
            Conn second = new Conn(target.getHost(), target.getPort(),
                    peerKey == null ? null : new Envelope(deviceKey(), peerKey, keyVersion));
            try {
                Received rsp = second.request(streamInfoRequest(url, streamKey), MSG_STREAM_INFO_REQ);
                Map<Integer, List<Object>> negotiated = Pb.decode(rsp.body);
                if (Pb.intValue(negotiated, 1) != 0) {
                    throw new IllegalStateException("VTDU result " + Pb.intValue(negotiated, 1));
                }
                return new Grab(second, Pb.bytes(negotiated, 4),
                        peerKey == null ? null : new Envelope(deviceKey()));
            } catch (Exception e) {
                second.close();
                throw e;
            }
        } finally {
            first.close();
        }
    }

    private static Bitmap readFrame(Grab grab) throws Exception {
        Conn conn = grab.conn;
        if (grab.ssn.length > 0) conn.send(Pb.str(1, grab.ssn), MSG_KEEPALIVE_REQ);
        conn.socket.setSoTimeout(15000);
        Joiner joiner = new Joiner();
        RtpDepay depay = new RtpDepay();
        int bareKeyframes = 0;
        long started = System.currentTimeMillis();
        while (System.currentTimeMillis() - started < 14000L) {
            Received frame = conn.recv();
            if (frame.code == MSG_KEEPALIVE_RSP) continue;
            byte[] packet = decryptMedia(grab.stream, frame.channel, frame.body);
            if (packet == null || packet.length == 0) continue;
            List<byte[]> packets = new ArrayList<>();
            if (frame.channel == CHANNEL_ECDH_MEDIA) {
                packets.addAll(joiner.feed(packet));
            } else {
                packets.add(packet);
            }
            for (byte[] rtp : packets) {
                for (byte[] unit : depay.feedRtp(rtp)) {
                    boolean hasSlice = false;
                    for (Nal nal : nals(unit)) {
                        if (nal.type >= 16 && nal.type <= 23) hasSlice = true;
                    }
                    if (!hasSlice) continue;
                    Bitmap bitmap = decode(unit);
                    if (bitmap != null) {
                        Log.i(TAG, "main-stream frame decoded: " + bitmap.getWidth() + "x"
                                + bitmap.getHeight());
                        return bitmap;
                    }
                    bareKeyframes++;
                    if (bareKeyframes > 3) return null;
                }
            }
        }
        return null;
    }

    private static byte[] decryptMedia(Envelope envelope, int channel, byte[] body) {
        if (channel == CHANNEL_MEDIA) return body;
        if ((channel != CHANNEL_ECDH_MEDIA && channel != CHANNEL_ECDH_RTP) || envelope == null) {
            return null;
        }
        byte[] head = channel == CHANNEL_ECDH_RTP
                ? Arrays.copyOf(body, Math.min(12, body.length)) : new byte[0];
        byte[] data;
        try {
            data = envelope.dec(body);
        } catch (Exception e) {
            return null;
        }
        if (data.length <= 1) return null;
        if (head.length == 0) return data;
        byte[] out = new byte[head.length + data.length];
        out[0] = (byte) (head[0] & 0xDF);
        System.arraycopy(head, 1, out, 1, head.length - 1);
        System.arraycopy(data, 0, out, head.length, data.length);
        return out;
    }

    private static byte[] streamInfoRequest(String url, byte[] streamKey) {
        byte[] body = Pb.str(1, url.getBytes(StandardCharsets.UTF_8));
        if (streamKey != null && streamKey.length > 0) body = concat(body, Pb.str(2, streamKey));
        body = concat(body, Pb.str(3, CLIENT_VERSION.getBytes(StandardCharsets.UTF_8)));
        body = concat(body, Pb.integer(4, 0));
        return concat(body, Pb.str(6, CLIENT_VERSION.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hostOf(String url) throws Exception {
        return URI.create(url).getHost();
    }

    private static int portOf(String url) throws Exception {
        return URI.create(url).getPort();
    }

    private static String encode(String text) throws Exception {
        return java.net.URLEncoder.encode(text, "UTF-8");
    }

    private static PrivateKey deviceKey() throws Exception {
        if (deviceKeys == null) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            deviceKeys = generator.generateKeyPair();
        }
        return deviceKeys.getPrivate();
    }

    private static byte[] devicePublic() throws Exception {
        deviceKey();
        return deviceKeys.getPublic().getEncoded();
    }

    /** Minimal protobuf wire format used by the ysproto control messages. */
    private static final class Pb {
        static byte[] varint(long value) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (true) {
                int b = (int) (value & 0x7F);
                value >>>= 7;
                out.write(value == 0 ? b : b | 0x80);
                if (value == 0) return out.toByteArray();
            }
        }

        static byte[] str(int field, byte[] value) {
            byte[] tag = varint((long) field << 3 | 2);
            byte[] length = varint(value.length);
            return concat(tag, concat(length, value));
        }

        static byte[] integer(int field, long value) {
            return concat(varint((long) field << 3), varint(value));
        }

        static Map<Integer, List<Object>> decode(byte[] data) {
            Map<Integer, List<Object>> result = new HashMap<>();
            int pos = 0;
            while (pos < data.length) {
                long key = readVarint(data, pos).value;
                pos = readVarint(data, pos).next;
                int wire = (int) (key & 7);
                int field = (int) (key >>> 3);
                Object value;
                if (wire == 0) {
                    Varint v = readVarint(data, pos);
                    value = v.value;
                    pos = v.next;
                } else if (wire == 2) {
                    Varint v = readVarint(data, pos);
                    pos = v.next;
                    int length = (int) v.value;
                    if (length < 0 || pos + length > data.length) break;
                    value = Arrays.copyOfRange(data, pos, pos + length);
                    pos += length;
                } else {
                    break;
                }
                List<Object> values = result.get(field);
                if (values == null) {
                    values = new ArrayList<>();
                    result.put(field, values);
                }
                values.add(value);
            }
            return result;
        }

        static byte[] bytes(Map<Integer, List<Object>> map, int field) {
            List<Object> values = map.get(field);
            if (values == null || values.isEmpty() || !(values.get(0) instanceof byte[])) {
                return new byte[0];
            }
            return (byte[]) values.get(0);
        }

        static long intValue(Map<Integer, List<Object>> map, int field) {
            List<Object> values = map.get(field);
            if (values == null || values.isEmpty() || !(values.get(0) instanceof Long)) return 0;
            return (Long) values.get(0);
        }

        private static Varint readVarint(byte[] data, int from) {
            long value = 0;
            int shift = 0;
            int pos = from;
            while (pos < data.length) {
                int b = data[pos++] & 0xFF;
                value |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return new Varint(value, pos);
                shift += 7;
            }
            return new Varint(value, pos);
        }

        private static final class Varint {
            final long value;
            final int next;

            Varint(long value, int next) {
                this.value = value;
                this.next = next;
            }
        }
    }

    /** One framed ysproto TCP connection. */
    private static final class Conn {
        final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final Envelope envelope;

        Conn(String host, int port, Envelope envelope) throws Exception {
            this.envelope = envelope;
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 10000);
            socket.setSoTimeout(20000);
            in = socket.getInputStream();
            out = socket.getOutputStream();
        }

        void send(byte[] body, int code) throws Exception {
            byte[] payload = envelope == null ? body : envelope.enc(body);
            int channel = envelope == null ? CHANNEL_MSG : CHANNEL_ECDH_MSG;
            out.write(frame(channel, code, payload));
            out.flush();
        }

        Received request(byte[] body, int code) throws Exception {
            send(body, code);
            return recv();
        }

        Received recv() throws Exception {
            byte[] header = readExact(8);
            if ((header[0] & 0xFF) != 0x24) {
                throw new IllegalStateException("bad ysproto header");
            }
            int channel = header[1] & 0xFF;
            int length = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
            int code = ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
            byte[] body = readExact(length);
            if (channel == CHANNEL_ECDH_MSG && envelope != null) body = envelope.dec(body);
            return new Received(channel, code, body);
        }

        private byte[] readExact(int length) throws Exception {
            byte[] data = new byte[length];
            int done = 0;
            while (done < length) {
                int n = in.read(data, done, length - done);
                if (n < 0) throw new IllegalStateException("stream closed");
                done += n;
            }
            return data;
        }

        void close() {
            try {
                socket.close();
            } catch (Exception ignored) {
                // nothing useful to do
            }
        }
    }

    private static byte[] frame(int channel, int code, byte[] body) {
        byte[] out = new byte[8 + body.length];
        out[0] = 0x24;
        out[1] = (byte) channel;
        out[2] = (byte) (body.length >>> 8);
        out[3] = (byte) body.length;
        out[6] = (byte) (code >>> 8);
        out[7] = (byte) code;
        System.arraycopy(body, 0, out, 8, body.length);
        return out;
    }

    private static final class Received {
        final int channel;
        final int code;
        final byte[] body;

        Received(int channel, int code, byte[] body) {
            this.channel = channel;
            this.code = code;
            this.body = body;
        }
    }

    private static final class Grab {
        final Conn conn;
        final byte[] ssn;
        final Envelope stream;

        Grab(Conn conn, byte[] ssn, Envelope stream) {
            this.conn = conn;
            this.ssn = ssn;
            this.stream = stream;
        }

        void close() {
            try {
                if (ssn.length > 0) conn.send(Pb.str(1, ssn), MSG_STOP_REQ);
            } catch (Exception ignored) {
                // the server closes the relay anyway
            }
            conn.close();
        }
    }

    /**
     * P-256 ECDH, ChaCha20 and HMAC-SHA256 envelope. Only the small subset used by the stream
     * relay is implemented; the ChaCha20 permutation is written out to avoid a platform dependency.
     */
    private static final class Envelope {
        private final PrivateKey privateKey;
        private final byte[] publicKey;
        private final byte[] master;
        private final int keyVersion;
        private byte[] session = new byte[32];
        private int sequence = 1;
        private boolean handshaked;

        Envelope(PrivateKey privateKey) throws Exception {
            this(privateKey, null, 1);
        }

        Envelope(PrivateKey privateKey, byte[] peerKey, int keyVersion) throws Exception {
            this.privateKey = privateKey;
            this.keyVersion = keyVersion;
            this.publicKey = devicePublic();
            this.master = peerKey == null || peerKey.length == 0 ? null : exchange(peerKey);
        }

        byte[] enc(byte[] data) throws Exception {
            if (!handshaked) {
                Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
                aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(master, "AES"));
                byte[] wrapped = aes.doFinal(session);
                ByteArrayOutputStream head = new ByteArrayOutputStream();
                head.write(0x24);
                head.write(0x01);
                head.write(0x00);
                write16(head, data.length);
                head.write(0x01);
                head.write(keyVersion);
                write32(head, 1);
                head.write(wrapped);
                head.write(publicKey);
                byte[] h = head.toByteArray();
                byte[] cipherText = chacha(session, 1, data);
                return concat(h, concat(cipherText, mac(master, h, cipherText)));
            }
            sequence++;
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            head.write(0x24);
            head.write(0x02);
            head.write(0x00);
            write16(head, data.length);
            write16(head, 0);
            write32(head, sequence);
            byte[] h = head.toByteArray();
            byte[] cipherText = chacha(session, sequence, data);
            return concat(h, concat(cipherText, mac(session, h, cipherText)));
        }

        byte[] dec(byte[] packageData) throws Exception {
            if (packageData.length < 11) throw new IllegalStateException("short envelope");
            int kind = packageData[1] & 0xFF;
            int ext = packageData[2] & 0xFF;
            int length = ((packageData[ext + 3] & 0xFF) << 8) | (packageData[ext + 4] & 0xFF);
            int seq = ((packageData[ext + 7] & 0xFF) << 24)
                    | ((packageData[ext + 8] & 0xFF) << 16)
                    | ((packageData[ext + 9] & 0xFF) << 8)
                    | (packageData[ext + 10] & 0xFF);
            byte[] macKey;
            int headLength;
            if (kind == 1) {
                headLength = ext + 134;
                macKey = exchange(Arrays.copyOfRange(packageData, ext + 43, headLength));
                Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
                aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(macKey, "AES"));
                session = aes.doFinal(Arrays.copyOfRange(packageData, ext + 11, ext + 43));
            } else if (kind == 2) {
                headLength = ext + 11;
                macKey = session;
            } else {
                throw new IllegalStateException("unknown envelope type " + kind);
            }
            if (headLength + length + 32 > packageData.length) {
                throw new IllegalStateException("truncated envelope");
            }
            byte[] cipherText = Arrays.copyOfRange(packageData, headLength, headLength + length);
            byte[] expected = Arrays.copyOfRange(packageData, headLength + length,
                    headLength + length + 32);
            if (!Arrays.equals(mac(macKey, Arrays.copyOf(packageData, headLength), cipherText),
                    expected)) {
                throw new IllegalStateException("envelope authentication failed");
            }
            handshaked = true;
            return chacha(session, seq, cipherText);
        }

        private byte[] exchange(byte[] peer) throws Exception {
            KeyFactory factory = KeyFactory.getInstance("EC");
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(privateKey);
            agreement.doPhase(factory.generatePublic(new X509EncodedKeySpec(peer)), true);
            return agreement.generateSecret();
        }
    }

    private static byte[] mac(byte[] key, byte[] head, byte[] cipherText) throws Exception {
        String text = Long.toString(crc(head)) + Long.toString(crc(cipherText));
        byte[] message = Arrays.copyOf(text.getBytes(StandardCharsets.US_ASCII), 8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(message);
    }

    private static long crc(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    private static void write16(ByteArrayOutputStream out, int value) {
        out.write(value >>> 8);
        out.write(value);
    }

    private static void write32(ByteArrayOutputStream out, long value) {
        out.write((int) (value >>> 24));
        out.write((int) (value >>> 16));
        out.write((int) (value >>> 8));
        out.write((int) value);
    }

    private static byte[] chacha(byte[] key, int sequence, byte[] data) {
        if (data.length == 0) return data;
        byte[] out = new byte[data.length];
        int[] state = new int[16];
        state[0] = 0x61707865;
        state[1] = 0x3320646e;
        state[2] = 0x79622d32;
        state[3] = 0x6b206574;
        for (int i = 0; i < 8; i++) state[4 + i] = littleEndian(key, i * 4);
        state[12] = 0;
        state[13] = sequence;
        state[14] = 0;
        state[15] = 0;
        byte[] block = new byte[64];
        for (int offset = 0; offset < data.length; offset += 64) {
            int[] work = state.clone();
            for (int round = 0; round < 10; round++) {
                quarter(work, 0, 4, 8, 12);
                quarter(work, 1, 5, 9, 13);
                quarter(work, 2, 6, 10, 14);
                quarter(work, 3, 7, 11, 15);
                quarter(work, 0, 5, 10, 15);
                quarter(work, 1, 6, 11, 12);
                quarter(work, 2, 7, 8, 13);
                quarter(work, 3, 4, 9, 14);
            }
            for (int i = 0; i < 16; i++) {
                int word = state[i] + work[i];
                block[i * 4] = (byte) word;
                block[i * 4 + 1] = (byte) (word >>> 8);
                block[i * 4 + 2] = (byte) (word >>> 16);
                block[i * 4 + 3] = (byte) (word >>> 24);
            }
            int count = Math.min(64, data.length - offset);
            for (int i = 0; i < count; i++) out[offset + i] = (byte) (data[offset + i] ^ block[i]);
            state[12]++;
        }
        return out;
    }

    private static void quarter(int[] x, int a, int b, int c, int d) {
        x[a] += x[b];
        x[d] = Integer.rotateLeft(x[d] ^ x[a], 16);
        x[c] += x[d];
        x[b] = Integer.rotateLeft(x[b] ^ x[c], 12);
        x[a] += x[b];
        x[d] = Integer.rotateLeft(x[d] ^ x[a], 8);
        x[c] += x[d];
        x[b] = Integer.rotateLeft(x[b] ^ x[c], 7);
    }

    private static int littleEndian(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    /** Reassembles the 1200-byte chunks used by the encrypted media channel. */
    private static final class Joiner {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private byte[] ssrc;

        List<byte[]> feed(byte[] chunk) {
            List<byte[]> out = new ArrayList<>();
            boolean starts = chunk.length >= 12 && ((chunk[0] & 0xFF) >>> 6) == 2;
            byte[] chunkSsrc = chunk.length >= 12
                    ? Arrays.copyOfRange(chunk, 8, 12) : new byte[0];
            if (starts && ssrc == null) ssrc = chunkSsrc;
            if (starts && Arrays.equals(chunkSsrc, ssrc)) {
                if (buffer.size() > 0) {
                    out.add(buffer.toByteArray());
                    buffer.reset();
                }
                if (chunk.length < ECDH_MEDIA_CHUNK) {
                    out.add(chunk);
                    return out;
                }
            } else if (buffer.size() == 0) {
                return out;
            }
            buffer.write(chunk, 0, chunk.length);
            if (chunk.length < ECDH_MEDIA_CHUNK) {
                out.add(buffer.toByteArray());
                buffer.reset();
            }
            return out;
        }
    }

    /** RTP payload depacketising for H.265 Annex-B access units. */
    private static final class RtpDepay {
        private final ByteArrayOutputStream unit = new ByteArrayOutputStream();
        private Long timestamp;

        List<byte[]> feedRtp(byte[] packet) {
            List<byte[]> out = new ArrayList<>();
            if (packet.length < 12 || ((packet[0] & 0xFF) >>> 6) != 2) return out;
            if ((packet[1] & 0x7F) != RTP_VIDEO_PAYLOAD_TYPE) return out;
            int start = 12 + 4 * (packet[0] & 0x0F);
            if ((packet[0] & 0x10) != 0 && packet.length >= start + 4) {
                start = start + 4 + 4 * (((packet[start + 2] & 0xFF) << 8)
                        | (packet[start + 3] & 0xFF));
            }
            int end = packet.length - ((packet[0] & 0x20) != 0 ? (packet[packet.length - 1] & 0xFF) : 0);
            if (end <= start) return out;
            long ts = ((long) (packet[4] & 0xFF) << 24) | ((packet[5] & 0xFF) << 16)
                    | ((packet[6] & 0xFF) << 8) | (packet[7] & 0xFF);
            if (timestamp != null && timestamp != ts && unit.size() > 0) {
                out.add(unit.toByteArray());
                unit.reset();
            }
            timestamp = ts;
            depay(Arrays.copyOfRange(packet, start, end));
            return out;
        }

        private void depay(byte[] payload) {
            if (payload.length <= 2) return;
            int nalType = (payload[0] & 0xFF) >> 1 & 0x3F;
            if (nalType == 48) {
                int pos = 2;
                while (pos + 2 <= payload.length) {
                    int size = ((payload[pos] & 0xFF) << 8) | (payload[pos + 1] & 0xFF);
                    if (size <= 0 || pos + 2 + size > payload.length) break;
                    unit.write(0); unit.write(0); unit.write(0); unit.write(1);
                    unit.write(payload, pos + 2, size);
                    pos += 2 + size;
                }
            } else if (nalType == 49) {
                int fu = payload[2] & 0xFF;
                if ((fu & 0x80) != 0) {
                    unit.write(0); unit.write(0); unit.write(0); unit.write(1);
                    unit.write((payload[0] & 0x81) | ((fu & 0x3F) << 1));
                    unit.write(payload[1]);
                }
                unit.write(payload, 3, payload.length - 3);
            } else {
                unit.write(0); unit.write(0); unit.write(0); unit.write(1);
                unit.write(payload, 0, payload.length);
            }
        }
    }

    private static final class Nal {
        final int type;
        final byte[] bytes;

        Nal(int type, byte[] bytes) {
            this.type = type;
            this.bytes = bytes;
        }
    }

    private static List<Nal> nals(byte[] data) {
        List<Nal> out = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i + 2 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) starts.add(i);
        }
        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i) + 3;
            int to = i + 1 < starts.size() ? starts.get(i + 1) : data.length;
            if (to < data.length && data[to - 1] == 0) to--;
            if (from >= to || from + 1 >= data.length) continue;
            int type = (data[from] & 0xFF) >> 1 & 0x3F;
            out.add(new Nal(type, Arrays.copyOfRange(data, Math.max(0, from - 3), to)));
        }
        return out;
    }

    /** Decodes one H.265 access unit to a Bitmap using the platform software decoder. */
    private static Bitmap decode(byte[] accessUnit) {
        MediaCodec codec = null;
        ImageReader reader = null;
        try {
            try {
                codec = MediaCodec.createByCodecName("c2.android.hevc.decoder");
            } catch (Exception software) {
                codec = MediaCodec.createByCodecName("OMX.google.hevc.decoder");
            }
            reader = ImageReader.newInstance(960, 1080, ImageFormat.YUV_420_888, 3);
            MediaFormat format = MediaFormat.createVideoFormat("video/hevc", 960, 1088);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024);
            codec.configure(format, reader.getSurface(), null, 0);
            codec.start();
            int input = codec.dequeueInputBuffer(4_000_000L);
            if (input < 0) return null;
            ByteBuffer buffer = codec.getInputBuffer(input);
            if (buffer == null) return null;
            buffer.clear();
            buffer.put(accessUnit);
            codec.queueInputBuffer(input, 0, accessUnit.length, 0, 0);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            for (int i = 0; i < 50; i++) {
                int output = codec.dequeueOutputBuffer(info, 100_000L);
                if (output >= 0) {
                    codec.releaseOutputBuffer(output, true);
                    for (int attempt = 0; attempt < 30; attempt++) {
                        Image image = reader.acquireLatestImage();
                        if (image != null) {
                            Bitmap bitmap = toBitmap(image);
                            image.close();
                            return bitmap;
                        }
                        Thread.sleep(10);
                    }
                    return null;
                }
            }
            return null;
        } catch (Exception e) {
            Log.w(TAG, "decode failed: " + e.getMessage());
            return null;
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignored) {
                    // best effort
                }
                codec.release();
            }
            if (reader != null) reader.close();
        }
    }

    private static Bitmap toBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length < 3) return null;
        int width = image.getWidth();
        int height = image.getHeight();
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        ByteBuffer y = planes[0].getBuffer();
        ByteBuffer u = planes[1].getBuffer();
        ByteBuffer v = planes[2].getBuffer();
        int yRow = planes[0].getRowStride();
        int yPixel = planes[0].getPixelStride();
        int uRow = planes[1].getRowStride();
        int uPixel = planes[1].getPixelStride();
        int vRow = planes[2].getRowStride();
        int vPixel = planes[2].getPixelStride();
        int[] pixels = new int[width * height];
        for (int row = 0; row < height; row++) {
            int yPos = row * yRow;
            int cPos = (row / 2) * uRow;
            for (int col = 0; col < width; col++) {
                int luma = (y.get(yPos + col * yPixel) & 0xFF) - 16;
                int cb = (u.get(cPos + (col / 2) * uPixel) & 0xFF) - 128;
                int cr = (v.get(cPos + (col / 2) * vPixel) & 0xFF) - 128;
                int r = clamp((298 * luma + 409 * cr + 128) >> 8);
                int g = clamp((298 * luma - 100 * cb - 208 * cr + 128) >> 8);
                int b = clamp((298 * luma + 516 * cb + 128) >> 8);
                pixels[row * width + col] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : (value > 255 ? 255 : value);
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) length += part.length;
        byte[] out = new byte[length];
        int pos = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, pos, part.length);
            pos += part.length;
        }
        return out;
    }
}
