package bigtestudp;

import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UDP server implementing a similar protocol to the TCP server used elsewhere.
 * - Receives text datagrams encoded UTF-8. Messages are commands like:
 *   ID:<id>, MSG:<text>, PRIV:target:msg, FILEDATA:name:b64, FILECHUNK:name:idx:b64, FILEEND:name, GETFILE:name, LIST, WHOAMI, BANK:..., POLL:...
 * - Maintains username -> InetSocketAddress mapping for addressing replies.
 * - Saves uploaded files into server_files/ (creates dir if missing).
 * - Chunked uploads supported via FILECHUNK/FILEEND (base64 chunks).
 */
public class Server {
    private static final int DEFAULT_PORT = 5000;
    private static final int BUFFER_SIZE = 65507; // max UDP payload
    private final DatagramSocket socket;
    private final Map<String, InetSocketAddress> clients = new ConcurrentHashMap<>();
    private final Map<String, String> rooms = new ConcurrentHashMap<>();
    private final Map<String, BankAccount> accounts = new ConcurrentHashMap<>();
    private final Map<Integer, Poll> polls = new ConcurrentHashMap<>();
    private final Map<String, SortedMap<Integer,String>> chunkBuffers = new ConcurrentHashMap<>();
    private final Path saveDir = Paths.get("server_files");
    private final SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private final Random rnd = new Random();
    private volatile String adminUser = null;
    private volatile boolean running = true;
    private int pollCounter = 0;

    // Feature toggles
    private static final boolean BROADCAST_ENABLED = true; // set false for server-simple unicast behavior

     // Predefined credentials for AUTH (optional server-side toggle in code)
     private final Map<String,String> credentials = new HashMap<>() {{ put("20520001","pass"); put("thao","hahaha"); }};
     private final boolean REQUIRE_AUTH = false; // toggle if you want AUTH over UDP (requires client to send AUTH:..)

    public Server(int port) throws SocketException {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket(port);
        } catch (BindException be) {
            log("Port " + port + " unavailable, binding to an ephemeral port instead.");
            try { s = new DatagramSocket(0); } catch (SocketException se) { throw se; }
        }
        socket = s;
        try { if (!Files.exists(saveDir)) Files.createDirectories(saveDir); } catch (IOException ignored) {}
        log("UDP server listening on port " + socket.getLocalPort());
    }

    private void log(String s) { System.out.println("[UDP-SRV] " + s); }

    private void sendTo(String msg, InetSocketAddress addr) {
        try {
            byte[] data = msg.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            DatagramPacket p = new DatagramPacket(data, data.length, addr.getAddress(), addr.getPort());
            socket.send(p);
            // Log the outgoing message for clear server activity tracing
            log("SEND to " + addrKey(addr) + " => " + msg);
        } catch (IOException e) { log("Send error to " + addr + ": " + e.getMessage()); }
    }

    private void broadcast(String msg) {
        for (InetSocketAddress a : clients.values()) sendTo(msg, a);
    }

    private void broadcastList() {
        String users = String.join(",", clients.keySet());
        Map<String,Integer> rm = new HashMap<>();
        for (String r : rooms.values()) rm.put(r, rm.getOrDefault(r,0)+1);
        String roomMap = rm.toString();
        broadcast("LIST:" + users + ":ROOMS:" + roomMap);
    }

    private String addrKey(InetSocketAddress a) { return a.getAddress().getHostAddress() + ":" + a.getPort(); }

    public void run() {
        byte[] buf = new byte[BUFFER_SIZE];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                String msg = new String(packet.getData(), packet.getOffset(), packet.getLength(), java.nio.charset.StandardCharsets.UTF_8);
                InetSocketAddress addr = new InetSocketAddress(packet.getAddress(), packet.getPort());
                handleMessage(msg, addr);
            } catch (IOException e) {
                log("Receive error: " + e.getMessage());
            }
        }
    }

    private void handleMessage(String line, InetSocketAddress addr) {
        try {
            log("RECV from " + addrKey(addr) + " => " + line.substring(0, Math.min(120, line.length())));
            // If the sender is not known yet and sends ID, register them
            String[] p = line.split(":", 3);
            String prefix = p[0];
            if (prefix.equals("AUTH") && REQUIRE_AUTH) {
                // AUTH:<user>:<pass>
                if (p.length>=3) {
                    String user = p[1]; String pass = p[2]; String exp = credentials.get(user);
                    if (exp!=null && exp.equals(pass)) { clients.put(user, addr); rooms.put(user, "Lobby"); sendTo("AUTH_OK", addr); if (adminUser==null) adminUser = user; broadcastList(); }
                    else sendTo("AUTH_FAIL", addr);
                } else sendTo("AUTH_FAIL", addr);
                return;
            }

            if (prefix.equals("ID") || (prefix.length()>0 && !clients.containsValue(addr) && clients.keySet().stream().noneMatch(k->k.equals(prefix)))) {
                // Accept both ID:<id> or plain id
                String rid = line.startsWith("ID:") ? line.substring(3).trim() : line.trim();
                String username;
                if (rid.matches("\\d+")) { username = "user" + rid; }
                else username = rid.isEmpty() ? "user" + rnd.nextInt(10000) : rid;
                synchronized (clients) {
                    if (clients.containsKey(username)) username = username + "_" + rnd.nextInt(1000);
                    clients.put(username, addr);
                    rooms.put(username, "Lobby");
                    if (adminUser==null) adminUser = username;
                }
                sendTo("ID_OK:" + username, addr);
                try { BigInteger n = new BigInteger(rid); sendTo("ID_RES:" + n.pow(4), addr); } catch (Exception ignored) {}
                if (BROADCAST_ENABLED) { broadcast("SYSTEM:" + username + " has joined"); broadcastList(); }
                return;
            }

            // find username by address
            String username = clients.entrySet().stream().filter(en -> en.getValue().equals(addr)).map(Map.Entry::getKey).findFirst().orElse(null);

            // File-related
            if (prefix.equals("FILEDATA") || prefix.equals("FILECHUNK") || prefix.equals("FILEEND") || prefix.equals("FILE") || prefix.equals("GETFILE")) {
                if (prefix.equals("FILE")) {
                    String name = line.substring(line.indexOf(':')+1);
                    sendTo("READY:" + name, addr);
                    return;
                }
                if (prefix.equals("FILEDATA")) {
                    int last = line.lastIndexOf(':');
                    if (last<=line.indexOf(':')) { sendTo("ERR:Malformed FILEDATA", addr); return; }
                    String name = line.substring(line.indexOf(':')+1, last);
                    String b64 = line.substring(last+1);
                    try {
                        byte[] data = Base64.getDecoder().decode(b64);
                        Path target = saveDir.resolve(name);
                        Files.write(target, data);
                        sendTo("FILE_OK:" + name + ":SAVED_AT:" + target.toAbsolutePath(), addr);
                        if (BROADCAST_ENABLED) broadcast("FILE_BC:" + (username==null?addrKey(addr):username) + ":" + name);
                    } catch (IllegalArgumentException iae) { sendTo("ERR:Base64 decode error:" + iae.getMessage(), addr); }
                    return;
                }
                if (prefix.equals("FILECHUNK")) {
                    // FILECHUNK:name:idx:b64 -> split into 4 parts, but b64 can contain ':' not typically, base64 doesn't include ':' so safe
                    String[] parts = line.split(":",4);
                    if (parts.length<4) { sendTo("ERR:Malformed FILECHUNK", addr); return; }
                    String name = parts[1]; int idx = Integer.parseInt(parts[2]); String b64 = parts[3];
                    String key = (username==null?addrKey(addr):username) + ":" + name;
                    SortedMap<Integer,String> buf = chunkBuffers.computeIfAbsent(key, k->new TreeMap<>());
                    buf.put(idx, b64);
                    sendTo("FILECHUNK_OK:" + name + ":" + idx, addr);
                    return;
                }
                if (prefix.equals("FILEEND")) {
                    String name = line.substring(line.indexOf(':')+1);
                    String key = (username==null?addrKey(addr):username) + ":" + name;
                    SortedMap<Integer,String> buf = chunkBuffers.get(key);
                    if (buf==null || buf.isEmpty()) { sendTo("FILE_ERR:No chunks for " + name, addr); return; }
                    try {
                        Path target = saveDir.resolve(name);
                        try (FileOutputStream fos = new FileOutputStream(target.toFile())) {
                            for (String b64 : buf.values()) fos.write(Base64.getDecoder().decode(b64));
                        }
                        chunkBuffers.remove(key);
                        sendTo("FILE_OK:" + name + ":SAVED_AT:" + target.toAbsolutePath(), addr);
                        if (BROADCAST_ENABLED) broadcast("FILE_BC:" + (username==null?addrKey(addr):username) + ":" + name);
                    } catch (Exception ex) { sendTo("ERR:File write failed:" + ex.getMessage(), addr); }
                    return;
                }
                if (prefix.equals("GETFILE")) {
                    String name = line.substring(line.indexOf(':')+1);
                    Path target = saveDir.resolve(name);
                    if (Files.exists(target)) {
                        try { byte[] data = Files.readAllBytes(target); String b64 = Base64.getEncoder().encodeToString(data); sendTo("FILE_SEND:" + name + ":" + b64, addr); } catch (Exception ex) { sendTo("ERR:File read failed:" + ex.getMessage(), addr); }
                    } else sendTo("FILE_SEND_ERR:Not found", addr);
                    return;
                }
            }

            // PRIV
            if (prefix.equals("PRIV")) {
                String[] t = line.split(":",3);
                if (t.length>=3) {
                    String target = t[1]; String msg = t[2]; InetSocketAddress tgt = clients.get(target);
                    if (tgt!=null) { sendTo("PRIV:" + (username==null?addrKey(addr):username) + ":" + msg, tgt); sendTo("PRIV_SENT:" + target + ":" + msg, addr); }
                    else sendTo("ERR:User not found", addr);
                } else sendTo("ERR:PRIV bad args", addr);
                return;
            }

            // CALC numeric
            if (line.matches("^-?\\d+$")) {
                try { BigInteger n = new BigInteger(line.trim()); BigInteger r = n.pow(4); sendTo("CALC_RES:" + r, addr); } catch (Exception ex) { sendTo("CALC_ERR:invalid", addr); }
                return;
            }

            // COMMANDS: LIST WHOAMI SAVELOG BANK POLL KICK QUIT
            if (prefix.equals("LIST")) {
                String users = String.join(",", clients.keySet()); Map<String,Integer> rcount = new HashMap<>(); for (String r : rooms.values()) rcount.put(r, rcount.getOrDefault(r,0)+1); sendTo("LIST:" + users + ":ROOMS:" + rcount, addr); return; }
            if (prefix.equals("WHOAMI")) { String who = (username==null?addrKey(addr):username); sendTo("WHOAMI:" + who + ":addr:" + addrKey(addr) + ":room:" + rooms.getOrDefault(who, "Lobby"), addr); return; }
            if (prefix.equals("SAVELOG")) { sendTo("SAVELOG_DONE", addr); return; }

            if (prefix.equals("BANK")) {
                String[] t = line.split(":",3);
                accounts.putIfAbsent((username==null?addrKey(addr):username), new BankAccount());
                BankAccount acc = accounts.get((username==null?addrKey(addr):username));
                if (t.length>=2) {
                    String act = t[1].toUpperCase();
                    try {
                        if (act.equals("DEPOSIT") && t.length>=3) { BigInteger amt = new BigInteger(t[2]); BigInteger bal = acc.deposit(amt); sendTo("BANK_OK:DEPOSIT:" + bal, addr); }
                        else if (act.equals("WITHDRAW") && t.length>=3) { BigInteger amt = new BigInteger(t[2]); try { BigInteger bal = acc.withdraw(amt); sendTo("BANK_OK:WITHDRAW:" + bal, addr); } catch (InterruptedException ie) { sendTo("BANK_ERR:timeout", addr); } }
                        else if (act.equals("BALANCE")) sendTo("BANK_BAL:" + acc.getBalance(), addr);
                        else sendTo("BANK_ERR:unknown", addr);
                    } catch (NumberFormatException nfe) { sendTo("BANK_ERR:bad_amount", addr); }
                } else sendTo("BANK_ERR:bad", addr);
                return;
            }

            if (prefix.equals("POLL")) {
                String[] t = line.split(":",4);
                if (t.length>=2) {
                    String act = t[1].toUpperCase();
                    if (act.equals("CREATE") && t.length>=4) {
                        int id = ++pollCounter;
                        List<String> opts = Arrays.asList(t[3].split(","));
                        Poll newPoll = new Poll(id, t[2], opts);
                        polls.put(id, newPoll);
                        broadcast("POLL_NEW:"+id+":"+t[2]+":"+opts);
                        sendTo("POLL_OK:CREATED:"+id, addr);
                    } else if (act.equals("VOTE") && t.length>=4) {
                        try {
                            int id = Integer.parseInt(t[2]);
                            int idx = Integer.parseInt(t[3]);
                            Poll pol = polls.get(id);
                            if (pol!=null) {
                                pol.vote(idx);
                                broadcast("POLL_RES:"+id+":"+pol.result());
                                sendTo("POLL_OK:VOTED", addr);
                            } else {
                                sendTo("POLL_ERR:unknown", addr);
                            }
                        } catch(Exception ex) {
                            sendTo("POLL_ERR", addr);
                        }
                    } else {
                        sendTo("POLL_ERR", addr);
                    }
                }
                return;
            }

            if (prefix.equals("KICK")) {
                String who = line.substring(line.indexOf(':')+1).trim(); if (!who.isEmpty() && adminUser!=null && adminUser.equals((username==null?addrKey(addr):username))) { InetSocketAddress t = clients.get(who); if (t!=null) { sendTo("SYSTEM:You are kicked by admin", t); clients.remove(who); rooms.remove(who); broadcast("SYSTEM:"+who+" was kicked by " + adminUser); } else sendTo("ERR:User not found", addr); } else sendTo("ERR:Not admin", addr); return; }

            if (prefix.equals("QUIT")) {
                String who = (username==null?addrKey(addr):username); clients.remove(who); rooms.remove(who); broadcast("SYSTEM:" + who + " is leaving"); broadcastList(); return;
            }

            // default: treat as plain text message
            String who = (username==null?addrKey(addr):username);
            log("MSG_FROM:" + who + ":" + line);
            String ts = df.format(new Date());
            if (BROADCAST_ENABLED) broadcast("MSG:" + who + ":" + ts + ":" + line);
            else sendTo("MSG:" + who + ":" + ts + ":" + line, addr);
            // private derived responses
            sendTo("TEXT_UPPER:" + line.toUpperCase(Locale.ROOT), addr);
            sendTo("TEXT_REV:" + new StringBuilder(line).reverse().toString(), addr);
            sendTo("COUNT_RES:" + line.length(), addr);
            sendTo("WCOUNT_RES:" + (line.trim().isEmpty()?0:line.trim().split("\\s+").length), addr);

        } catch (Exception e) { log("handleMessage exception: " + e.getMessage()); sendTo("ERR:ServerException:" + e.getMessage(), addr); }
    }

    // Bank account
    static class BankAccount {
        private BigInteger balance = BigInteger.ZERO;
        private final List<String> history = new ArrayList<>();
        public synchronized BigInteger deposit(BigInteger amt) { balance = balance.add(amt); history.add("DEPOSIT:"+amt+":BAL="+balance); notifyAll(); return balance; }
        public synchronized BigInteger withdraw(BigInteger amt) throws InterruptedException { long deadline = System.currentTimeMillis()+10000; while (balance.compareTo(amt)<0) { long w=deadline-System.currentTimeMillis(); if (w<=0) throw new IllegalStateException("Insufficient"); wait(w);} balance = balance.subtract(amt); history.add("WITHDRAW:"+amt+":BAL="+balance); return balance; }
        public synchronized BigInteger getBalance(){ return balance; }
    }

    // Poll
    static class Poll {
        final int id;
        final String title;
        final List<String> opts;
        final Map<Integer,Integer> votes = new HashMap<>();
        Poll(int id,String t, List<String> o){
            this.id=id; this.title=t; this.opts=o; for(int i=0;i<o.size();i++) votes.put(i,0);
        }
        void vote(int i){ votes.put(i, votes.getOrDefault(i,0)+1); }
        String result(){
            StringBuilder sb=new StringBuilder();
            for(int i=0;i<opts.size();i++){
                if (i>0) sb.append(",");
                sb.append(opts.get(i)).append("=").append(votes.getOrDefault(i,0));
            }
            return sb.toString();
        }
    }

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        if (args.length>=1) try { port = Integer.parseInt(args[0]); } catch (Exception ignored) {}
        Server srv = new Server(port);
        srv.run();
    }
}
