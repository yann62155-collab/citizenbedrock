package fr.citizen.bedrock.selftest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.Inflater;

/**
 * Faux client Minecraft 1.21.1 qui se présente exactement comme le pont (adresse + données Floodgate),
 * pour vérifier toute la connexion d'un joueur console sur un vrai serveur pendant l'autotest.
 */
final class FakeBedrockClient {
    private static final int PROTOCOL_1_21_1 = 767;

    private final String host;
    private final int port;
    private final String floodgateData;
    private final String name;
    private int compression = -1;

    record Result(boolean reachedPlay, boolean encryptionRequested, int chunks, String disconnectReason, String summary) {
    }

    FakeBedrockClient(String host, int port, String floodgateData, String name) {
        this.host = host;
        this.port = port;
        this.floodgateData = floodgateData;
        this.name = name;
    }

    Result connect(Runnable onPlay) throws IOException {
        TreeMap<String, Integer> seen = new TreeMap<>();
        boolean play = false;
        boolean encryption = false;
        int chunks = 0;
        String disconnect = null;
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(30000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            // Poignée de main : adresse + \0 + données chiffrées (comme Geyser)
            ByteArrayOutputStream hs = new ByteArrayOutputStream();
            writeVarInt(hs, PROTOCOL_1_21_1);
            writeString(hs, "localhost\0" + floodgateData);
            hs.write((port >> 8) & 0xFF);
            hs.write(port & 0xFF);
            writeVarInt(hs, 2);
            send(out, 0x00, hs.toByteArray());

            ByteArrayOutputStream hello = new ByteArrayOutputStream();
            writeString(hello, name.replace(' ', '_'));
            DataOutputStream helloData = new DataOutputStream(hello);
            UUID offline = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            helloData.writeLong(offline.getMostSignificantBits());
            helloData.writeLong(offline.getLeastSignificantBits());
            send(out, 0x00, hello.toByteArray());

            String phase = "login";
            long playUntil = Long.MAX_VALUE;
            while (System.currentTimeMillis() < playUntil) {
                byte[] packet;
                try {
                    packet = read(in);
                } catch (SocketTimeoutException | EOFException e) {
                    break;
                }
                DataInputStream p = new DataInputStream(new ByteArrayInputStream(packet));
                int id = readVarInt(p);
                seen.merge(phase + ":0x" + Integer.toHexString(id), 1, Integer::sum);
                switch (phase) {
                    case "login" -> {
                        if (id == 0x00) {
                            disconnect = "login: " + readString(p);
                            return new Result(false, encryption, chunks, disconnect, seen.toString());
                        } else if (id == 0x01) {
                            encryption = true;
                            return new Result(false, true, chunks, "encryption", seen.toString());
                        } else if (id == 0x03) {
                            compression = readVarInt(p);
                        } else if (id == 0x04) {
                            ByteArrayOutputStream r = new ByteArrayOutputStream();
                            writeVarInt(r, readVarInt(p));
                            r.write(0);
                            send(out, 0x02, r.toByteArray());
                        } else if (id == 0x02) {
                            send(out, 0x03, new byte[0]); // login acknowledged
                            phase = "config";
                        }
                    }
                    case "config" -> {
                        if (id == 0x02) {
                            disconnect = "configuration (" + packet.length + " octets)";
                            return new Result(false, encryption, chunks, disconnect, seen.toString());
                        } else if (id == 0x03) {
                            send(out, 0x03, new byte[0]); // finish configuration acknowledged
                            phase = "play";
                        } else if (id == 0x04) {
                            send(out, 0x04, p.readNBytes(8));
                        } else if (id == 0x05) {
                            send(out, 0x05, p.readNBytes(4));
                        } else if (id == 0x0E) {
                            ByteArrayOutputStream r = new ByteArrayOutputStream();
                            writeVarInt(r, 0);
                            send(out, 0x07, r.toByteArray());
                        }
                    }
                    case "play" -> {
                        if (id == 0x1D) {
                            disconnect = "jeu (" + packet.length + " octets)";
                            return new Result(play, encryption, chunks, disconnect, seen.toString());
                        } else if (id == 0x2B) {
                            play = true;
                            playUntil = System.currentTimeMillis() + 9000;
                            Thread.ofVirtual().start(() -> {
                                try {
                                    Thread.sleep(1500);
                                } catch (InterruptedException ignored) {
                                }
                                onPlay.run();
                            });
                        } else if (id == 0x19) {
                            // Menu Bedrock (canal floodgate:form) : on répond « bouton 1 » comme le ferait Geyser
                            String channel = readString(p);
                            if (channel.equals("floodgate:form")) {
                                byte[] data = p.readAllBytes();
                                ByteArrayOutputStream r = new ByteArrayOutputStream();
                                writeString(r, "floodgate:form");
                                r.write(data[1]);
                                r.write(data[2]);
                                r.write("1".getBytes(StandardCharsets.UTF_8));
                                send(out, 0x12, r.toByteArray());
                            }
                        } else if (id == 0x27) {
                            chunks++;
                        } else if (id == 0x26) {
                            send(out, 0x18, p.readNBytes(8));
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        return new Result(play, encryption, chunks, disconnect, seen.toString());
    }

    private void send(OutputStream out, int id, byte[] payload) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarInt(body, id);
        body.write(payload);
        byte[] data = body.toByteArray();
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        if (compression >= 0) {
            ByteArrayOutputStream inner = new ByteArrayOutputStream();
            writeVarInt(inner, 0); // non compressé
            inner.write(data);
            data = inner.toByteArray();
        }
        writeVarInt(frame, data.length);
        frame.write(data);
        out.write(frame.toByteArray());
        out.flush();
    }

    private byte[] read(DataInputStream in) throws IOException {
        int length = readVarInt(in);
        byte[] frame = in.readNBytes(length);
        if (frame.length < length) throw new EOFException();
        if (compression < 0) return frame;
        DataInputStream f = new DataInputStream(new ByteArrayInputStream(frame));
        int dataLength = readVarInt(f);
        byte[] rest = f.readAllBytes();
        if (dataLength == 0) return rest;
        Inflater inflater = new Inflater();
        inflater.setInput(rest);
        byte[] result = new byte[dataLength];
        try {
            int done = 0;
            while (done < dataLength && !inflater.finished()) {
                int n = inflater.inflate(result, done, dataLength - done);
                if (n == 0 && inflater.needsInput()) break;
                done += n;
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException(e);
        } finally {
            inflater.end();
        }
        return result;
    }

    private static void writeVarInt(OutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        int position = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new EOFException();
            value |= (b & 0x7F) << position;
            if ((b & 0x80) == 0) return value;
            position += 7;
            if (position >= 32) throw new IOException("VarInt trop long");
        }
    }

    private static void writeString(OutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static String readString(InputStream in) throws IOException {
        int len = readVarInt(in);
        return new String(in.readNBytes(len), StandardCharsets.UTF_8);
    }
}
