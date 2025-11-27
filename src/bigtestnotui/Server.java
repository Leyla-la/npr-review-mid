package bigtestnotui;

import java.io.*;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Console-only Server (bigtestnotui.Server)
 * - Implements the same protocol as bigtest.Server but without GUI.
 * - Supports: ID/AUTH handshake, MSG broadcast, PRIV, FILE upload/download (base64 chunked or single-pkt), numeric handling (n^4), LIST, WHOAMI, BANK (simple), POLL (create/vote)
 * - Comment markers included to enable/disable optional templates (see UNCOMMENT_* tags).
 */
public class Server {
    private final int port;
    private volatile boolean running = false;
    private ServerSocket serverSocket;

    // state
    private final Map<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private final Map<String, String> rooms = new ConcurrentHashMap<>();
    private final Map<String, BankAccount> accounts = new ConcurrentHashMap<>();
    private final Map<Integer, Poll> polls = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, String>> chunkBuffers = new ConcurrentHashMap<>();

    private final SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private final Path SAVE_DIR = Paths.get("server_files");
    private final Random rnd = new Random();
    private volatile String adminUser = null;
    private int pollCounter = 0;

    // auth credential map (simple)
    private final Map<String,String> credentials = new HashMap<>() {{ put("20520001","pass"); put("thao","hahaha"); }};
    private boolean requireAuth = false; // toggle here to require AUTH by default

    // feature toggles
    private static final boolean BROADCAST_ENABLED = true;
    private static final boolean ENABLE_PRIVMSG = true;
    private static final boolean ENABLE_BANK = true;
    private static final boolean ENABLE_POLL = true;

    public Server(int port) {
        this.port = port;
        try {
            if (!Files.exists(SAVE_DIR)) Files.createDirectories(SAVE_DIR);
        } catch (IOException ignored) {}
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        log("Server started on port " + port);
        while (running) {
            Socket s = serverSocket.accept();
            String addr = s.getInetAddress().getHostAddress() + ":" + s.getPort();
            log("Incoming: " + addr);
            ClientHandler ch = new ClientHandler(s, addr);
            new Thread(ch).start();
        }
    }

    private void broadcast(String msg) {
        for (ClientHandler ch : clients.values()) ch.send(msg);
    }

    private void broadcastList() {
        String users = String.join(",", clients.keySet());
        Map<String,Integer> rc = new HashMap<>();
        for (String r : rooms.values()) rc.put(r, rc.getOrDefault(r,0)+1);
        String roomMap = rc.toString();
        for (ClientHandler ch : clients.values()) ch.send("LIST:" + users + ":ROOMS:" + roomMap);
    }

    private void log(String s) { System.out.println("[SERVER] " + s); }

    // helpers
    private static boolean isPrime(BigInteger n) { if (n.compareTo(BigInteger.TWO) < 0) return false; return n.isProbablePrime(20); }
    private static int digitSum(BigInteger n) { String s = n.abs().toString(); int sum=0; for(char c: s.toCharArray()) sum += c - '0'; return sum; }

    private class ClientHandler implements Runnable {
        final Socket socket;
        final BufferedReader in;
        final PrintWriter out;
        final String clientId; // address-based id for logging before username assigned
        String username;
        String rawId;
        String room = "Lobby";
        // per-handler chunk buffer (filename -> idx->base64) stored in outer map if needed
        final Map<String, SortedMap<Integer,String>> localChunks = new HashMap<>();

        ClientHandler(Socket s, String id) throws IOException {
            this.socket = s;
            this.in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            this.out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            this.username = id; // provisional until ID handshake
            this.clientId = id;
        }

        // Send a raw line and log it (use clientId when username isn't finalized)
        private void sendRawAndLog(String line) {
            out.println(line);
            out.flush();
            log("SEND to " + (username == null ? clientId : username) + " => " + line);
        }


        public void send(String line) {
            out.println(line);
            log("SEND to " + username + " => " + line);
        }

        @Override
        public void run() {
            try {
                String line = in.readLine();
                if (line == null) { socket.close(); return; }
                // AUTH flow
                if (requireAuth) {
                    if (!line.startsWith("AUTH:")) {
                        sendRawAndLog("AUTH_REQ");
                        line = in.readLine();
                        if (line == null) { socket.close(); return; }
                    }
                    if (line.startsWith("AUTH:")) {
                        String[] p = line.split(":" ,3);
                        String user = p.length>1?p[1].trim():"";
                        String pass = p.length>2?p[2].trim():"";
                        String exp = credentials.get(user);
                        if (exp!=null && exp.equals(pass)) {
                            sendRawAndLog("AUTH_OK"); username = user; }
                        else {
                            sendRawAndLog("AUTH_FAIL"); socket.close(); return;
                        }
                    } else {
                        sendRawAndLog("AUTH_FAIL"); socket.close(); return;
                    }
                } else {
                    // accept ID:<id> or plain
                    String rid;
                    if (line.startsWith("ID:")) rid = line.substring(3).trim(); else rid = line.trim();
                    if (rid.matches("\\d+")) { rawId = rid; username = "user" + rid; } else { rawId = null; if (!rid.isEmpty()) username = rid; }
                }

                // ensure unique username
                synchronized (clients) {
                    if (username==null || username.isEmpty()) username = "user" + rnd.nextInt(10000);
                    if (clients.containsKey(username)) username = username + "_" + rnd.nextInt(1000);
                    if (adminUser==null) adminUser = username;
                    clients.put(username, this);
                    rooms.put(username, room);
                }

                log("Connected: " + username + " from " + socket.getRemoteSocketAddress());

                // send ID_OK and ID_RES if rawId present
                send("ID_OK:" + username);
                if (rawId != null) {
                    try { BigInteger n = new BigInteger(rawId); send("ID_RES:" + n.pow(4)); } catch(Exception ignored) {}
                }
                if (BROADCAST_ENABLED) { broadcast("SYSTEM:" + username + " has joined"); broadcastList(); }
                else send("SYSTEM:Connected (server-simple-mode)");

                // main loop
                while ((line = in.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    handleLine(line);
                }
            } catch (IOException e) {
                log("Client error: " + e.getMessage());
            } finally {
                cleanup();
            }
        }

        private void cleanup() {
            try { socket.close(); } catch (IOException ignored) {}
            if (username != null) {
                clients.remove(username);
                rooms.remove(username);
                if (BROADCAST_ENABLED) { broadcast("SYSTEM:" + username + " has left"); broadcastList(); }
                if (username.equals(adminUser)) adminUser = clients.keySet().stream().findFirst().orElse(null);
                log("Disconnected: " + username);
            }
        }

        private void handleLine(String line) {
            try {
                // similar parsing as GUI server: check FILE family, PRIV etc.
                String[] p = line.split(":", 3);
                String prefix = p[0];
                int firstColon = line.indexOf(':');

                if ("FILE".equals(prefix) || "FILEDATA".equals(prefix) || "FILECHUNK".equals(prefix) || "FILEEND".equals(prefix) || "GETFILE".equals(prefix)) {
                    try {
                        if ("FILE".equals(prefix)) {
                            String name = line.substring(firstColon+1);
                            send("READY:" + name);
                            return;
                        }
                        if ("FILEDATA".equals(prefix)) {
                            int lastColon = line.lastIndexOf(':');
                            if (lastColon <= firstColon) { send("ERR:Malformed FILEDATA"); return; }
                            String name = line.substring(firstColon+1, lastColon);
                            String b64 = line.substring(lastColon+1);
                            byte[] data = Base64.getDecoder().decode(b64);
                            Path target = SAVE_DIR.resolve(name);
                            try (FileOutputStream fos = new FileOutputStream(target.toFile())) { fos.write(data); }
                            send("FILE_OK:" + name + ":SAVED_AT:" + target.toAbsolutePath());
                            if (BROADCAST_ENABLED) broadcast("FILE_BC:" + username + ":" + name);
                            return;
                        }
                        if ("FILECHUNK".equals(prefix)) {
                            int lastColon = line.lastIndexOf(':');
                            int secondLast = line.lastIndexOf(':', lastColon-1);
                            if (secondLast <= firstColon || lastColon <= secondLast) { send("ERR:Malformed FILECHUNK"); return; }
                            String name = line.substring(firstColon+1, secondLast);
                            String idxS = line.substring(secondLast+1, lastColon);
                            String b64 = line.substring(lastColon+1);
                            int idx = Integer.parseInt(idxS);
                            SortedMap<Integer,String> buf = localChunks.computeIfAbsent(name, k->new TreeMap<>());
                            buf.put(idx, b64);
                            send("FILECHUNK_OK:" + name + ":" + idx);
                            return;
                        }
                        if ("FILEEND".equals(prefix)) {
                            String name = line.substring(firstColon+1);
                            SortedMap<Integer,String> buf = localChunks.get(name);
                            if (buf==null || buf.isEmpty()) { send("FILE_ERR:No chunks for " + name); return; }
                            Path target = SAVE_DIR.resolve(name);
                            try (FileOutputStream fos = new FileOutputStream(target.toFile())) {
                                for (Map.Entry<Integer,String> e : buf.entrySet()) {
                                    byte[] part = Base64.getDecoder().decode(e.getValue()); fos.write(part);
                                }
                            }
                            localChunks.remove(name);
                            send("FILE_OK:" + name + ":SAVED_AT:" + target.toAbsolutePath());
                            if (BROADCAST_ENABLED) broadcast("FILE_BC:" + username + ":" + name);
                            return;
                        }
                        if ("GETFILE".equals(prefix)) {
                            String name = line.substring(firstColon+1);
                            Path target = SAVE_DIR.resolve(name);
                            if (Files.exists(target)) {
                                byte[] data = Files.readAllBytes(target);
                                String b64 = Base64.getEncoder().encodeToString(data);
                                send("FILE_SEND:" + name + ":" + b64);
                            } else send("FILE_SEND_ERR:Not found");
                            return;
                        }
                    } catch (IllegalArgumentException iae) {
                        send("ERR:Base64 decode error: " + iae.getMessage()); log("Base64 decode error for " + username + ": " + iae.getMessage()); return;
                    } catch (Exception ex) { send("ERR:File handling exception: " + ex.getMessage()); log("File handling exception: " + ex); return; }
                }

                if (line.startsWith("PRIV:")) {
                    String[] t = line.split(":",3);
                    if (t.length>=3) {
                        String target = t[1].trim(); String msg = t[2];
                        ClientHandler tgt = clients.get(target);
                        if (tgt!=null) { tgt.send("PRIV:" + username + ":" + msg); send("PRIV_SENT:" + target + ":" + msg); }
                        else send("ERR:User not found");
                    } else send("ERR:PRIV bad args");
                    return;
                }

                // treat as body (no prefix required)
                String body = line.trim();
                // numeric detection
                if (body.matches("^-?\\d+$")) {
                    try {
                        BigInteger n = new BigInteger(body);
                        send("CALC_RES:" + n.pow(4));
                        if (n.compareTo(BigInteger.ZERO) > 0) send("NUM_SIGN:POS");
                        else if (n.compareTo(BigInteger.ZERO) < 0) send("NUM_SIGN:NEG");
                        else send("NUM_SIGN:ZERO");
                        // optional diagnostics (commented in server GUI) — enable by editing source
                        return;
                    } catch (Exception ex) { send("NUM_ERR:invalid"); return; }
                }

                // plain text: broadcast
                String ts = df.format(new Date());
                log("MSG_FROM:" + username + ":" + body);
                if (BROADCAST_ENABLED) broadcast("MSG:" + username + ":" + ts +":" + body);
                else send("MSG:" + username + ":" + ts +":" + body);

                // private derived responses
                send("TEXT_UPPER:" + body.toUpperCase(Locale.ROOT));
                send("TEXT_REV:" + new StringBuilder(body).reverse().toString());
                send("COUNT_RES:" + body.length());
                send("WCOUNT_RES:" + (body.trim().isEmpty()?0:body.trim().split("\\\\s+").length));

            } catch (Exception e) { send("ERR:Exception " + e.getClass().getSimpleName() + " - " + e.getMessage()); log("Exception in handler: " + e); }
        }
    }

    // Simple bank account
    private static class BankAccount {
        private BigInteger balance = BigInteger.ZERO;
        private final List<String> history = new ArrayList<>();
        public synchronized BigInteger deposit(BigInteger amt) { balance = balance.add(amt); history.add("DEPOSIT:" + amt + ":BAL=" + balance); notifyAll(); return balance; }
        public synchronized BigInteger withdraw(BigInteger amt) throws InterruptedException { long deadline = System.currentTimeMillis() + 10000; while (balance.compareTo(amt) < 0) { long w = deadline - System.currentTimeMillis(); if (w<=0) throw new IllegalStateException("Insufficient"); wait(w); } balance = balance.subtract(amt); history.add("WITHDRAW:"+amt+":BAL="+balance); return balance; }
        public synchronized BigInteger getBalance(){ return balance; }
        public synchronized List<String> getHistory(){ return new ArrayList<>(history); }
    }

    // Poll
    private static class Poll { final int id; final String title; final List<String> opts; final Map<Integer,Integer> votes = new HashMap<>(); Poll(int id,String t,List<String> o){ this.id=id; this.title=t; this.opts=o; for(int i=0;i<o.size();i++) votes.put(i,0);} void vote(int i){ votes.put(i, votes.getOrDefault(i,0)+1);} String result(){ StringBuilder sb=new StringBuilder(); for(int i=0;i<opts.size();i++){ if(i>0) sb.append(","); sb.append(opts.get(i)).append("=").append(votes.getOrDefault(i,0)); } return sb.toString(); } }

    public static void main(String[] args) throws Exception {
        int port = 5000;
        Server s = new Server(port);
        s.start();
    }
}

