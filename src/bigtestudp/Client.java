package bigtestudp;

import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Scanner;

/**
 * Console UDP client compatible with bigtestudp.Server protocol.
 * - Sends ID:<id> at startup, then supports menu-driven commands.
 * - Uses DatagramSocket; best-effort delivery (no reliability layer implemented yet).
 */
public class Client {
    // Optional auto-connect constants
    private static final boolean AUTO_CONNECT = false; // set true to auto-connect without prompts
    private static final String AUTO_HOST = "localhost";
    private static final int AUTO_PORT = 5000;
    private static final String AUTO_ID = "20520001";

    // Simple menu toggles
    private static final boolean SIMPLE_MENU = true;

    // Chunk size for UDP file chunking (raw bytes). Keep below ~40KB to be safe after base64 expansion.
    private static final int RAW_CHUNK_SIZE = 40000;

    private final String host;
    private final int port;
    private DatagramSocket socket;
    private InetAddress serverAddr;
    private int serverPort;
    private final Scanner sc = new Scanner(System.in);
    private volatile boolean running = true;

    public Client(String host, int port) throws Exception { this.host = host; this.port = port; this.serverAddr = InetAddress.getByName(host); this.serverPort = port; socket = new DatagramSocket(); socket.setSoTimeout(0); }

    private void send(String s) {
        try {
            byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            DatagramPacket p = new DatagramPacket(b, b.length, serverAddr, serverPort);
            socket.send(p);
        } catch (IOException e) { System.out.println("[UDP-CLIENT] Send error: " + e.getMessage()); }
    }

    private void listenAsync() {
        new Thread(() -> {
            byte[] buf = new byte[65507];
            while (running) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    socket.receive(p);
                    String m = new String(p.getData(), p.getOffset(), p.getLength(), java.nio.charset.StandardCharsets.UTF_8);
                    System.out.println("[SRV] " + m);
                    System.out.print("[You] > ");
                } catch (IOException e) { if (running) System.out.println("[UDP-CLIENT] Receive error: " + e.getMessage()); }
            }
        }, "udp-listener").start();
    }

    private void sendFileSingle(Path p) throws Exception {
        String name = p.getFileName().toString();
        byte[] data = Files.readAllBytes(p);
        String b64 = Base64.getEncoder().encodeToString(data);
        send("FILEDATA:" + name + ":" + b64);
        System.out.println("[FILE] Sent single-packet " + name + " (" + data.length + " bytes)");
    }

    private void sendFileChunked(Path p) throws Exception {
        String name = p.getFileName().toString();
        byte[] data = Files.readAllBytes(p);
        int total = data.length;
        int idx = 0; int off = 0; int chunks = (total + RAW_CHUNK_SIZE - 1) / RAW_CHUNK_SIZE;
        System.out.println("[FILE] Uploading in " + chunks + " chunks...");
        while (off < total) {
            int len = Math.min(RAW_CHUNK_SIZE, total - off);
            byte[] part = new byte[len]; System.arraycopy(data, off, part, 0, len);
            String b64 = Base64.getEncoder().encodeToString(part);
            send("FILECHUNK:" + name + ":" + idx + ":" + b64);
            off += len; idx++;
            try { Thread.sleep(30); } catch (InterruptedException ignored) {}
        }
        send("FILEEND:" + name);
        System.out.println("[FILE] Chunked upload finished: " + name);
    }

    private void sendFile(String path) {
        try {
            Path p = Paths.get(path);
            if (!Files.exists(p)) { System.out.println("[FILE] Path not found: " + path); return; }
            long size = Files.size(p);
            if (size <= RAW_CHUNK_SIZE) sendFileSingle(p); else sendFileChunked(p);
        } catch (Exception e) { System.out.println("[FILE] Err: " + e.getMessage()); }
    }

    public void run(String id) {
        listenAsync();
        send("ID:" + id);
        System.out.println("Connected (UDP). Type 'help' for commands.");
        while (running) {
            try {
                if (SIMPLE_MENU) {
                    System.out.println("--- Menu: choose an option number or type a plain message ---");
                    System.out.println("1) Send message   2) Send file   3) Private message   4) List   5) Whoami");
                    System.out.println("6) Bank actions   7) Poll actions  8) Help   9) Quit");
                    System.out.print("Choice or message: ");
                } else {
                    System.out.print("[You] > ");
                }
                String line = sc.nextLine().trim();
                if (line.isEmpty()) continue;
                if (SIMPLE_MENU && line.matches("^[1-9]$")) {
                    int choice = Integer.parseInt(line);
                    switch (choice) {
                        case 1:
                            System.out.print("Enter message: "); String m = sc.nextLine(); if (m!=null) send(m); break;
                        case 2:
                            System.out.print("Enter absolute file path: "); String fp = sc.nextLine(); if (fp!=null) sendFile(fp.trim()); break;
                        case 3:
                            System.out.print("Target username: "); String tgt = sc.nextLine(); System.out.print("Message: "); String pm = sc.nextLine(); if (tgt!=null && pm!=null) send("PRIV:" + tgt.trim() + ":" + pm); break;
                        case 4: send("LIST"); break;
                        case 5: send("WHOAMI"); break;
                        case 6:
                            System.out.println("Bank: a) deposit  b) withdraw  c) balance"); System.out.print("Choice: "); String bch = sc.nextLine(); if ("a".equalsIgnoreCase(bch)) { System.out.print("Amount: "); String av = sc.nextLine(); send("BANK:DEPOSIT:" + av.trim()); }
                            else if ("b".equalsIgnoreCase(bch)) { System.out.print("Amount: "); String av2 = sc.nextLine(); send("BANK:WITHDRAW:" + av2.trim()); }
                            else if ("c".equalsIgnoreCase(bch)) send("BANK:BALANCE"); break;
                        case 7:
                            System.out.println("Poll: a) create  b) vote"); System.out.print("Choice: "); String pch = sc.nextLine(); if ("a".equalsIgnoreCase(pch)) { System.out.print("Title: "); String title = sc.nextLine(); System.out.print("Options (comma): "); String opts = sc.nextLine(); send("POLL:CREATE:" + title + ":" + opts); }
                            else if ("b".equalsIgnoreCase(pch)) { System.out.print("Poll id: "); String pid = sc.nextLine(); System.out.print("Option index: "); String idx = sc.nextLine(); send("POLL:VOTE:" + pid.trim() + ":" + idx.trim()); } break;
                        case 8:
                            System.out.println("Commands: message | /file <path> | /priv <user> <msg> | /list | /whoami | /quit"); break;
                        case 9:
                            send("QUIT"); running = false; continue;
                    }
                    continue;
                }

                // fallback commands
                if (line.equalsIgnoreCase("/quit") || line.equalsIgnoreCase("quit")) { send("QUIT"); break; }
                if (line.equalsIgnoreCase("/help") || line.equalsIgnoreCase("help")) { System.out.println("Commands: message | /file <path> | /priv <user> <msg> | /list | /whoami | /quit"); continue; }
                if (line.startsWith("/file ")) { sendFile(line.substring(6).trim()); continue; }
                if (line.startsWith("/priv ")) { String[] t = line.split("\\s+",3); if (t.length<3) { System.out.println("Usage: /priv user msg"); continue; } send("PRIV:" + t[1] + ":" + t[2]); continue; }
                if (line.equalsIgnoreCase("/list")) { send("LIST"); continue; }
                if (line.equalsIgnoreCase("/whoami")) { send("WHOAMI"); continue; }

                // otherwise send message as-is
                send(line);
            } catch (Exception e) { System.out.println("[UDP-CLIENT] Error: " + e.getMessage()); }
        }
        running = false; socket.close();
    }

    public static void main(String[] args) throws Exception {
        String host; int port; String id;
        if (AUTO_CONNECT) { host = AUTO_HOST; port = AUTO_PORT; id = AUTO_ID; System.out.println("AUTO_CONNECT enabled: host="+host+" port="+port+" id="+id); }
        else { Scanner s = new Scanner(System.in); System.out.print("Server host [enter for localhost]: "); host = s.nextLine().trim(); if (host.isEmpty()) host = "localhost"; System.out.print("Server port [enter for 5000]: "); String ps = s.nextLine().trim(); port = 5000; if (!ps.isEmpty()) try { port = Integer.parseInt(ps); } catch (Exception ignored) {} System.out.print("Enter your student id or username: "); id = s.nextLine().trim(); if (id.isEmpty()) id = "anon" + (int)(Math.random()*1000); }
        Client c = new Client(host, port);
        c.run(id);
    }
}
