package bigtest;

import java.awt.*;
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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.*; // GUI
import javax.net.ssl.*;

/* bigtest.Server - compact multi-client chat server (GUI optional).
   Features: MSG/PRIV/FILE/BANK/POLL/ROOM/LIST/WHOAMI/KICK/SAVELOG, optional AUTH and SSL.
*/

// compact server: GUI + protocol handlers. (Detailed docs removed to keep file short.)
public class Server {
    // Configuration
    private final int port;
    private ServerSocket serverSocket;
    private volatile boolean isRunning = false; // server running flag

    // -------------------- GUI FIELDS --------------------
    // Server GUI components (pink theme, Times New Roman for logs)
    private JFrame frame;
    private JTextArea txtLog;                // main server log area (Times New Roman)
    private DefaultListModel<String> clientListModel;
    private JList<String> clientList;
    private JButton btnStart;
    private JButton btnStop;
    // ---------------------------------------------------

     // Global state
     private final Map<String, ClientHandler> clients = new ConcurrentHashMap<>(); // username -> handler
     @SuppressWarnings("unused")
     private final Map<String, BankAccount> accounts = new ConcurrentHashMap<>(); // username -> bank
     @SuppressWarnings("unused")
     private final Map<Integer, Poll> polls = new ConcurrentHashMap<>(); // pollId -> Poll
     private final Map<String, String> rooms = new ConcurrentHashMap<>(); // username -> roomName
     private final SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    // In-memory chat history (used by SAVELOG feature). We keep it in-memory since disk logging is disabled.
    private final List<String> chatHistory = Collections.synchronizedList(new ArrayList<>());

    // Admin (first connected user)
    private volatile String adminUser = null;

    // Logs
    private final Path chatLog = Paths.get("chat_log.txt");
    private final Path bankLog = Paths.get("bank_log.txt");

    // ----------------- FILE STORAGE OPTIONS -----------------
    // By default files uploaded by clients are saved into a folder named "server_files"
    // under the server working directory. If you prefer to place saved files inside the
    // project package (for submission or inspection), set SAVE_IN_PACKAGE = true and
    // PACKAGE_SAVE_DIR will be used (e.g. src/bigtest/server_files). This is optional.
    @SuppressWarnings("unused")
    private static final boolean SAVE_IN_PACKAGE = false; // set true to store files under PACKAGE_SAVE_DIR
    @SuppressWarnings("unused")
    private static final String PACKAGE_SAVE_DIR = "src/bigtest/server_files"; // relative to project root
    @SuppressWarnings("unused")
    private static final String DEFAULT_SAVE_DIR = "server_files"; // relative to working dir
    // --------------------------------------------------------

    // Poll id generator
    private final Random rnd = new Random();
    @SuppressWarnings("unused")
    private int pollCounter = 0;

    // ----------------- OPTIONAL: SIMPLE AUTHENTICATION -----------------
    // Runtime-toggleable auth flag. Use the GUI checkbox "Require AUTH" to
    // enable/disable authentication while the server is running. Default=true.
    private volatile boolean enableAuth = false;
    // pre-defined credentials (username -> password). You can modify or load dynamically.
    private final Map<String,String> credentials = new HashMap<>() {{ put("thao","hahaha"); put("20520001","pass"); }};
    // -----------------------------------------------------------------

    // Optional SSL/TLS: set to true and configure SSL_KEYSTORE/SSL_PASS to enable.
    private static final boolean ENABLE_SSL = false; // set true to bind SSLServerSocket
    private static final String SSL_KEYSTORE = "./server_keystore.jks";
    private static final String SSL_KEYSTORE_PASS = "123456";

    // TEST_SIMPLE_MODE removed. Use explicit feature flags below to control optional behaviors.
    // Set these to true to enable feature; set to false to comment/disable the behavior.
    private static final boolean BROADCAST_ENABLED = true; // when true server broadcasts joins/rooms/file notifications
    private static final boolean ENABLE_POLL = true;       // enable poll creation/vote
    private static final boolean ENABLE_BANK = true;       // enable bank (deposit/withdraw) features
    private static final boolean ENABLE_KICK = true;       // admin kick
    private static final boolean ENABLE_SAVELOG = true;    // allow SAVELOG command
    private static final boolean ENABLE_PRIVMSG = true;    // private messaging (PRIV)

    // Default AUTH requirement: set to true to require AUTH by default; the GUI checkbox
    // was removed per request — toggle here in code if you want AUTH enabled or disabled.
    private static final boolean REQUIRE_AUTH_DEFAULT = false;

    public Server(int port) {
        this.port = port;
        // ensure logs exist
        try {
            // NOTE: per request we no longer create or write chat_log.txt / bank_log.txt
            // Files.createFile(chatLog); Files.createFile(bankLog); // (disabled)
            // ensure file storage directory exists for uploads
            Path d = Paths.get(DEFAULT_SAVE_DIR);
            if (!Files.exists(d)) Files.createDirectories(d);
        } catch (IOException ignored) {}
        // initialize auth flag from compile-time default
        this.enableAuth = REQUIRE_AUTH_DEFAULT;
        // Initialize GUI components (but do not auto-start server). Call initializeGUI() from main.
    }

    // -------------------- GUI METHODS --------------------
    /**
     * Build and show the server GUI. Use Start/Stop to control the listener.
     * The GUI is intentionally simple: log area, client list, start/stop controls.
     */
    public void initializeGUI() {
        SwingUtilities.invokeLater(() -> {
            frame = new JFrame("NPR Server - Pink GUI");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(900, 600);

            // Top control panel with Start/Stop
            JPanel control = new JPanel(new FlowLayout(FlowLayout.LEFT));
            btnStart = new JButton("Start Server");
            btnStop = new JButton("Stop Server"); btnStop.setEnabled(false);
            control.add(btnStart); control.add(btnStop);

            // Log area (Times New Roman)
            txtLog = new JTextArea(); txtLog.setEditable(false);
            txtLog.setFont(new Font("Times New Roman", Font.PLAIN, 12));
            txtLog.setBackground(new Color(255, 240, 245)); // pale pink
            JScrollPane logScroll = new JScrollPane(txtLog);

            // Client list (with titled border that shows connected count)
            clientListModel = new DefaultListModel<>(); clientList = new JList<>(clientListModel);
            clientList.setFont(new Font("Times New Roman", Font.PLAIN, 13));
            JScrollPane listScroll = new JScrollPane(clientList);
            listScroll.setBorder(BorderFactory.createTitledBorder("Clients (0)"));

            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, logScroll, listScroll);
            split.setDividerLocation(650);

            frame.getContentPane().setBackground(new Color(255, 192, 203)); // pink theme background
            frame.getContentPane().setLayout(new BorderLayout());
            frame.getContentPane().add(control, BorderLayout.NORTH);
            frame.getContentPane().add(split, BorderLayout.CENTER);

            // Wire buttons
            btnStart.addActionListener(e -> startServer());
            btnStop.addActionListener(e -> stopServer());

            frame.setVisible(true);
            appendConsole("GUI initialized. Port: " + port + " — click Start Server to listen.");
        });
    }

    // Append message to GUI log and to file log
    private void appendConsole(String s) {
        appendChatLog("CONSOLE:" + s);
        if (txtLog != null) {
            SwingUtilities.invokeLater(() -> {
                txtLog.append(s + "\n");
                txtLog.setCaretPosition(txtLog.getDocument().getLength());
            });
        }
    }

    // Update client list UI
    private void updateClientListUI() {
        if (clientListModel == null) return;
        SwingUtilities.invokeLater(() -> {
            clientListModel.clear();
            for (String u : clients.keySet()) clientListModel.addElement(u);
            // update titled border with count
            int cnt = clients.size();
            if (clientList != null) {
                Component parent = clientList.getParent();
                if (parent instanceof JViewport) parent = parent.getParent();
                if (parent instanceof JScrollPane) {
                    JScrollPane sp = (JScrollPane) parent;
                    sp.setBorder(BorderFactory.createTitledBorder("Clients (" + cnt + ")"));
                }
            }
            appendConsole("Connected clients: " + cnt);
        });
    }

    // Start the server accept loop (called from GUI Start)
    public void startServer() {
        if (isRunning) return;
        try {
            if (ENABLE_SSL) {
                // Create SSL context and configure the server socket factory
                System.setProperty("javax.net.ssl.keyStore", SSL_KEYSTORE);
                System.setProperty("javax.net.ssl.keyStorePassword", SSL_KEYSTORE_PASS);
                SSLServerSocketFactory sslFactory = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
                serverSocket = sslFactory.createServerSocket(port);
            } else {
                serverSocket = new ServerSocket(port);
            }
            isRunning = true;
            btnStart.setEnabled(false);
            btnStop.setEnabled(true);
            appendConsole("Server started on port " + port);

            Thread acceptThread = new Thread(() -> {
                while (isRunning) {
                    try {
                        Socket s = serverSocket.accept();
                        String clientId = "Client connected from " + s.getInetAddress().getHostAddress() + ":" + s.getPort();
                        appendConsole(clientId);
                        // create handler and start in its own thread
                        ClientHandler ch = new ClientHandler(s, clientId);
                        new Thread(ch).start();
                    } catch (IOException e) {
                        if (isRunning) appendConsole("Accept error: " + e.getMessage());
                    }
                }
                appendConsole("Accept loop ended");
            });
            acceptThread.start();
        } catch (IOException e) {
            appendConsole("Failed to start server: " + e.getMessage());
        }
    }

    // Stop the server and disconnect clients
    public void stopServer() {
        if (!isRunning) return;
        isRunning = false;
        try {
            // close server socket to break accept()
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        } catch (IOException ignored) {}
        // close all client sockets
        for (ClientHandler ch : new ArrayList<>(clients.values())) {
            try { ch.socket.close(); } catch (IOException ignored) {}
        }
        clients.clear();
        updateClientListUI();
        btnStart.setEnabled(true); btnStop.setEnabled(false);
        appendConsole("Server stopped");
    }

    // ---------------------------------------------------
    // Legacy CLI start kept for compatibility; prefer startServer() (GUI)
    public void start() throws IOException {
        // Start server in non-GUI mode (blocking) if called directly from main
        serverSocket = new ServerSocket(port);
        logConsole("Server started on port " + port + " (CLI mode)");
        while (!serverSocket.isClosed()) {
            Socket s = serverSocket.accept();
            String clientId = "Client connected from " + s.getInetAddress().getHostAddress() + ":" + s.getPort();
            logConsole(clientId);
            ClientHandler ch = new ClientHandler(s, clientId);
            new Thread(ch).start();
        }
    }

    // Broadcast utility: send message to all clients
    private void broadcast(String line) {
        for (ClientHandler ch : clients.values()) ch.send(line);
    }

    // Broadcast to room
    private void broadcastRoom(String room, String line) {
        for (Map.Entry<String, ClientHandler> e : clients.entrySet()) {
            String user = e.getKey();
            if (room.equals(rooms.getOrDefault(user, "Lobby"))) e.getValue().send(line);
        }
    }

    // NO DISK CHAT LOG (disabled by request). We still show messages in GUI console.
    private synchronized void appendChatLog(String entry) {
        String line = df.format(new Date()) + " | " + entry + System.lineSeparator();
        // write only to GUI / console (no file writes)
        logConsole(line);
        // keep in-memory history for SAVELOG
        chatHistory.add(line.trim());
        if (txtLog != null) {
            SwingUtilities.invokeLater(() -> {
                txtLog.append(entry + "\n");
                txtLog.setCaretPosition(txtLog.getDocument().getLength());
            });
        }
    }

    // NO DISK BANK LOG (disabled by request). We still record bank ops in memory and GUI.
    private synchronized void appendBankLog(String entry) {
        // keep visible in server GUI/hard-log only; do NOT write to bank_log.txt
        appendChatLog("BANK:" + entry);
    }

    // Simple console logger (server-side GUI was optional; for exam CLI is fine)
    private void logConsole(String s) { System.out.println(s); }

    // Find handler by username
    private ClientHandler findHandler(String username) { return clients.get(username); }

    // Bank account implementation using BigInteger and wait/notify
    private static class BankAccount {
        private BigInteger balance = BigInteger.ZERO;
        private final List<String> history = new ArrayList<>();

        public synchronized BigInteger deposit(BigInteger amt) {
            balance = balance.add(amt);
            history.add("DEPOSIT:" + amt + ":BAL=" + balance);
            notifyAll(); // wake waiting withdrawers
            return balance;
        }

        public synchronized BigInteger withdraw(BigInteger amt) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 10000; // wait up to 10s
            while (balance.compareTo(amt) < 0) {
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) throw new IllegalStateException("Insufficient funds");
                wait(wait);
            }
            balance = balance.subtract(amt);
            history.add("WITHDRAW:" + amt + ":BAL=" + balance);
            return balance;
        }

        public synchronized BigInteger getBalance() { return balance; }
        public synchronized List<String> getHistory() { return new ArrayList<>(history); }
    }

    // Poll class
    private static class Poll {
        final int id;
        final String title;
        final List<String> options;
        final Map<Integer, Integer> votes = new HashMap<>();

        Poll(int id, String title, List<String> options) {
            this.id = id; this.title = title; this.options = options;
            for (int i = 0; i < options.size(); i++) votes.put(i, 0);
        }

        public synchronized void vote(int idx) { votes.put(idx, votes.getOrDefault(idx, 0) + 1); }

        public synchronized String resultString() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < options.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(options.get(i)).append("=").append(votes.getOrDefault(i, 0));
            }
            return sb.toString();
        }
    }

    // ClientHandler: handles a single connected socket
    private class ClientHandler implements Runnable {
        private final Socket socket;
        private final BufferedReader in;
        private final PrintWriter out;
        private final String clientId; // descriptive address-based id (not the user name)
        private String username = null; // the display username (may be 'user'+studentId or custom)
        private String rawId = null;    // the original ID sent by client (if any) - used to compute ID_RES when numeric
        private String room = "Lobby";
        // Buffer for incoming chunked file uploads: filename -> (index -> base64chunk)
        private final Map<String, SortedMap<Integer,String>> chunkBuffers = new HashMap<>();

        // Constructor changed to accept clientId for clearer logging and admin decisions.
        ClientHandler(Socket s, String clientId) throws IOException {
            this.socket = s;
            this.clientId = clientId;
            this.in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            this.out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
        }

        // send a single-line message
        public void send(String line) {
            // Log outgoing message to server GUI & chat log
            appendConsole("SEND to " + username + " => " + line);
            out.println(line);
        }

        @Override
        public void run() {
            try {
                String line;
                // Optional AUTH flow: if ENABLE_AUTH == true, expect client to send
                // AUTH:<username>:<password> as the first meaningful message. If the
                // client sends something else, server replies AUTH_REQ and waits.
                line = in.readLine();
                if (line == null) return;
                if (enableAuth) {
                    // ensure we have an AUTH: token
                    if (!line.startsWith("AUTH:")) {
                        out.println("AUTH_REQ"); out.flush();
                        line = in.readLine();
                        if (line == null) return;
                    }
                    if (line.startsWith("AUTH:")) {
                        String[] parts = line.split(":", 3);
                        String au = parts.length>1?parts[1].trim():"";
                        String ap = parts.length>2?parts[2].trim():"";
                        String expected = credentials.get(au);
                        if (expected != null && expected.equals(ap)) {
                            out.println("AUTH_OK"); out.flush();
                            username = au; // authenticated user becomes username
                        } else {
                            out.println("AUTH_FAIL"); out.flush();
                            appendConsole("Auth failed for " + au + " from " + clientId);
                            socket.close();
                            return;
                        }
                    } else {
                        out.println("AUTH_FAIL"); out.flush(); socket.close(); return;
                    }
                } else {
                    // legacy behavior: accept ID:<id> or plain username as first token
                    String rid;
                    if (line.startsWith("ID:")) {
                        rid = line.substring(3).trim();
                    } else {
                        rid = line.trim();
                    }
                    // If the client provided a purely numeric student ID, convert to display username 'user<ID>'
                    if (rid.matches("\\d+")) {
                         rawId = rid;
                         username = "user" + rid; // canonical display name
                     } else {
                         // non-numeric: treat the provided token as username
                         rawId = null;
                         username = rid.isEmpty() ? null : rid;
                     }
                }

                // Ensure unique username: if exists, append random suffix
                synchronized (clients) {
                    if (username == null || username.isEmpty()) username = "user" + rnd.nextInt(10000);
                    if (clients.containsKey(username)) username = username + "_" + rnd.nextInt(1000);
                    // If admin not set, first connected becomes admin
                    if (adminUser == null) adminUser = username;
                    clients.put(username, this);
                    rooms.put(username, room);
                }

                // Print both clientId and username for clarity
                logConsole("Handler started for " + clientId + " -> username=" + username);
                updateClientListUI();

                // Send ID_OK and ID_RES (ID^4) as required
                send("ID_OK:" + username);
                // If we have rawId (numeric student id), compute rawId^4 and send ID_RES
                if (rawId != null) {
                    try {
                        BigInteger n = new BigInteger(rawId);
                        send("ID_RES:" + n.pow(4));
                    } catch (Exception ignore) {}
                } else {
                    // if username itself is numeric (edge-case), try compute
                    try {
                        BigInteger n = new BigInteger(username);
                        send("ID_RES:" + n.pow(4));
                    } catch (Exception ignore) {}
                }
                // Announce join. By default BROADCAST_ENABLED==true and server broadcasts joins and LIST.
                appendChatLog("JOIN:" + username + ":" + clientId);
                updateClientListUI();
                if (BROADCAST_ENABLED) {
                    broadcast("SYSTEM:" + username + " has joined");
                    // Broadcast current online LIST to all clients so they can update in real-time
                    broadcastList();
                } else {
                    // If you want "simple-mode" (server replies only to the connecting client), set BROADCAST_ENABLED=false above.
                    send("SYSTEM:Connected (server-simple-mode)");
                }

                // main read loop
                while ((line = in.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    handleLine(line);
                }
            } catch (IOException e) {
                logConsole("Client error (" + clientId + "): " + e.getMessage());
            } finally {
                cleanup();
            }
        }

        private void cleanup() {
            try { socket.close(); } catch (IOException ignored) {}
            if (username != null) {
                clients.remove(username);
                rooms.remove(username);
                // Broadcast updated LIST so clients see the change immediately
                if (BROADCAST_ENABLED) {
                    broadcast("SYSTEM:" + username + " has left");
                    appendChatLog("LEFT:" + username);
                    updateClientListUI();
                    // Broadcast updated LIST so clients see the change immediately
                    broadcastList();
                    if (username.equals(adminUser)) {
                        // new admin: pick any existing user
                        adminUser = clients.keySet().stream().findFirst().orElse(null);
                        if (adminUser != null) broadcast("SYSTEM:New admin is " + adminUser);
                    }
                }
            }
        }

        // Build and broadcast LIST message to all clients (simple CSV of usernames + rooms summary)
        private void broadcastList() {
            String users = String.join(",", clients.keySet());
            Map<String,Integer> roomCounts = new HashMap<>();
            for (String r : rooms.values()) roomCounts.put(r, roomCounts.getOrDefault(r, 0) + 1);
            String roomMap = roomCounts.toString();
            for (ClientHandler ch : clients.values()) ch.send("LIST:" + users + ":ROOMS:" + roomMap);
        }

        // Handle a single incoming prefixed line
        private void handleLine(String line) {
            String[] p = line.split(":", 3);
            // Simplified, prefix-free handling:
            // - File-related commands (FILE, FILEDATA, FILECHUNK, FILEEND, GETFILE) are processed as before.
            // - PRIV:<target>:<msg> still supported for private messages.
            // - If the incoming content is a plain integer -> numeric handler (one active case implemented: n^4).
            // - Otherwise treat as plain text: broadcast message to room and return a small set of derived string results
            //   (UPPER, REV, COUNT, WCOUNT) directly to the sender.
            try {
                // quick guard
                if (line == null || line.trim().isEmpty()) return;

                // FILE family: preserve earlier robust handling that tolerates Windows paths with ':'
                String prefix = p[0];
                if ("FILE".equals(prefix) || "FILEDATA".equals(prefix) || "FILECHUNK".equals(prefix) || "FILEEND".equals(prefix) || "GETFILE".equals(prefix)) {
                    int firstColon = line.indexOf(':');
                    if (firstColon < 0) { send("ERR:Malformed file command"); return; }
                    try {
                        if ("FILE".equals(prefix)) {
                            String abs = line.substring(firstColon + 1);
                            String name = Paths.get(abs).getFileName().toString();
                            send("READY:" + name);
                            return;
                        }
                        if ("FILEDATA".equals(prefix)) {
                            int lastColon = line.lastIndexOf(':');
                            if (lastColon <= firstColon) { send("ERR:Malformed FILEDATA"); return; }
                            String abs = line.substring(firstColon + 1, lastColon);
                            String b64 = line.substring(lastColon + 1);
                            byte[] data = Base64.getDecoder().decode(b64);
                            String name = Paths.get(abs).getFileName().toString();
                            Path target = Paths.get(DEFAULT_SAVE_DIR, name);
                            try (FileOutputStream fos = new FileOutputStream(target.toFile())) { fos.write(data); }
                            appendChatLog("FILE_UP:" + username + ":" + name + ":" + target.toAbsolutePath());
                            send("FILE_OK:" + name + ":SAVED_AT:" + target.toAbsolutePath());
                            if (BROADCAST_ENABLED) broadcastRoom(room, "FILE_BC:" + username + ":" + name);
                            return;
                        }
                        if ("FILECHUNK".equals(prefix)) {
                            int lastColon = line.lastIndexOf(':');
                            int secondLast = line.lastIndexOf(':', lastColon - 1);
                            if (secondLast <= firstColon || lastColon <= secondLast) { send("ERR:Malformed FILECHUNK"); return; }
                            String abs = line.substring(firstColon + 1, secondLast);
                            String idxS = line.substring(secondLast + 1, lastColon);
                            String b64 = line.substring(lastColon + 1);
                            int idx = Integer.parseInt(idxS);
                            String name = Paths.get(abs).getFileName().toString();
                            SortedMap<Integer, String> buf = chunkBuffers.computeIfAbsent(name, k -> new TreeMap<>());
                            buf.put(idx, b64);
                            send("FILECHUNK_OK:" + name + ":" + idx);
                            return;
                        }
                        if ("FILEEND".equals(prefix)) {
                            String abs = line.substring(firstColon + 1);
                            String name = Paths.get(abs).getFileName().toString();
                            SortedMap<Integer, String> buf = chunkBuffers.get(name);
                            if (buf == null || buf.isEmpty()) { send("FILE_ERR:No chunks for " + name); return; }
                            Path target = Paths.get(DEFAULT_SAVE_DIR, name);
                            try (FileOutputStream fos = new FileOutputStream(target.toFile())) {
                                for (Map.Entry<Integer, String> e : buf.entrySet()) {
                                    byte[] part = Base64.getDecoder().decode(e.getValue());
                                    fos.write(part);
                                }
                            }
                            chunkBuffers.remove(name);
                            appendChatLog("FILE_UP:" + username + ":" + name + ":" + target.toAbsolutePath());
                            send("FILE_OK:" + name + ":SAVED_AT:" + target.toAbsolutePath());
                            if (BROADCAST_ENABLED) broadcastRoom(room, "FILE_BC:" + username + ":" + name);
                            return;
                        }
                        if ("GETFILE".equals(prefix)) {
                            String name = line.substring(firstColon + 1);
                            Path target = Paths.get(DEFAULT_SAVE_DIR, name);
                            if (Files.exists(target)) {
                                byte[] data = Files.readAllBytes(target);
                                String b64 = Base64.getEncoder().encodeToString(data);
                                send("FILE_SEND:" + name + ":" + b64);
                            } else send("FILE_SEND_ERR:Not found");
                            return;
                        }
                    } catch (IllegalArgumentException iae) {
                        send("ERR:Base64 decode error: " + iae.getMessage());
                        appendConsole("Base64 decode error for client " + username + ": " + iae.getMessage());
                        return;
                    } catch (Exception ex) {
                        send("ERR:File handling exception: " + ex.getMessage());
                        appendConsole("File handling exception: " + ex);
                        return;
                    }
                }

                // PRIVATE message (kept simple): PRIV:target:msg (optional prefix kept for private sends)
                if (line.startsWith("PRIV:")) {
                    String[] t = line.split(":",3);
                    if (t.length >= 3) {
                        String target = t[1].trim(); String msg = t[2];
                        ClientHandler tgt = findHandler(target);
                        if (tgt != null) { tgt.send("PRIV:" + username + ":" + msg); send("PRIV_SENT:" + target + ":" + msg); appendChatLog("PRIV_FROM:" + username + ":TO:" + target + ":" + msg); }
                        else send("ERR:User not found");
                    } else send("ERR:PRIV bad args");
                    return;
                }

                // Now: treat the entire line as user content (no prefix required)
                String body = line.trim();

                /*
                 * ====== GUIDE: Enabling Broadcast / Private / Server-only modes ======
                 *
                 * Current active behavior (default):
                 *   - The server broadcasts incoming plain text to the room using:
                 *       broadcast("MSG:" + username + ":" + ts + ":" + body);
                 *   - The server also sends a few private derived string responses back to the sender.
                 *
                 * If you want to change how plain text is routed, use one of the templates below.
                 * Each template is self-contained: uncomment the block you want and (optionally)
                 * comment out the existing broadcast line further down (search for 'broadcast("MSG:' ).
                 *
                 * 1) SERVER-ONLY (no broadcast): reply only to the sender (unicast)
                 *    -> Uncomment the UNCOMMENT_SERVER_ONLY_TEMPLATE block and ensure the
                 *       `broadcast(...)` call is commented out.
                 *
                 *    // UNCOMMENT_SERVER_ONLY_TEMPLATE START
                 *    // // send only to the originating client (unicast)
                 *    // send("MSG:" + username + ":" + ts + ":" + body);
                 *    // UNCOMMENT_SERVER_ONLY_TEMPLATE END
                 *
                 * 2) PRIVATE MESSAGE format (user can send PRIV:target:message)
                 *    -> This is already supported in the file-handling above but you can
                 *       enable stricter parsing here. If a user types "@target message" or
                 *       "PRIV:target:msg" you can parse and forward only to that target.
                 *
                 *    // UNCOMMENT_PRIV_TEMPLATE START
                 *    // if (body.startsWith("@")) {
                 *    //     // format: @target message
                 *    //     int sp = body.indexOf(' ');
                 *    //     if (sp>1) {
                 *    //         String target = body.substring(1, sp).trim();
                 *    //         String msg = body.substring(sp+1);
                 *    //         ClientHandler tgt = findHandler(target);
                 *    //         if (tgt!=null) tgt.send("PRIV:" + username + ":" + msg);
                 *    //         else send("SYSTEM:User not found: " + target);
                 *    //         return; // consumed
                 *    //     }
                 *    // }
                 *    // UNCOMMENT_PRIV_TEMPLATE END
                 *
                 * 3) BROADCAST vs ECHO toggle (GUI checkbox alternative)
                 *    - If you want the server to default to echo/unicast (reply only to sender)
                 *      and provide a GUI checkbox to toggle broadcast behavior, use the block below.
                 *    - Replace 'broadcastCheckbox' with your actual JCheckBox field if present.
                 *
                 *    // UNCOMMENT_BROADCAST_TEMPLATE START
                 *    // boolean doBroadcast = true; // default
                 *    // // if you have GUI checkbox: doBroadcast = (broadcastCheckbox!=null && broadcastCheckbox.isSelected());
                 *    // String formatted = "MSG:" + username + ":" + ts + ":" + body;
                 *    // if (doBroadcast) broadcast(formatted); else send(formatted);
                 *    // UNCOMMENT_BROADCAST_TEMPLATE END
                 *
                 * ---------------- Note on streams and file transfer ----------------
                 * The server currently uses character streams for messages (BufferedReader/PrintWriter)
                 * and Base64-encoded blocks for file upload/download (client sends FILEDATA/FILECHUNK etc).
                 * This is implemented using:
                 *   - Reader: BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF-8));
                 *   - Writer: PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), UTF-8), true);
                 *   - For storing binary files on disk we use FileOutputStream and Base64 decoding.
                 *
                 * If you prefer raw binary transfers that do not require Base64 and avoid colon parsing
                 * issues (recommended for large files), use the DataOutputStream/DataInputStream pattern.
                 * Below is a small server-side skeleton (COMMENTED) you can enable. It must be used in
                 * coordination with a client-side DataOutputStream sender (see Client.java template).
                 *
                 *    // UNCOMMENT_BINARY_SERVER_TEMPLATE START
                 *    // // Example server-side binary receive (call from run-loop when expecting binary):
                 *    // DataInputStream dis = new DataInputStream(socket.getInputStream());
                 *    // String hdr = dis.readUTF(); // e.g. BFILE:<name>:<length>
                 *    // // parse hdr then read exact number of bytes into file
                 *    // long length = ...; // parsed
                 *    // try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                 *    //    byte[] buf = new byte[8192]; long rem = length; while (rem>0) { int r = dis.read(buf,0,(int)Math.min(buf.length, rem)); if (r<0) throw new EOFException(); fos.write(buf,0,r); rem-=r; }
                 *    // }
                 *    // UNCOMMENT_BINARY_SERVER_TEMPLATE END
                 *
                 * When you uncomment any of the above templates: recompile server and client together.
                 * I added explicit marker comments (UNCOMMENT_SERVER_ONLY_TEMPLATE, UNCOMMENT_PRIV_TEMPLATE,
                 * UNCOMMENT_BROADCAST_TEMPLATE, UNCOMMENT_BINARY_SERVER_TEMPLATE) so you can quickly find
                 * and enable them.
                 */

                // Numeric detection (simple integer check). Active numeric behavior: compute n^4 and return to sender.
                if (body.matches("^-?\\d+$")) {
                    try {
                        BigInteger n = new BigInteger(body);
                        // ACTIVE: compute n^4 (required by spec) and send back to sender
                        BigInteger pow4 = n.pow(4);
                        send("CALC_RES:" + pow4);

                        // ACTIVE: sign info (simple human-friendly tag)
                        if (n.compareTo(BigInteger.ZERO) > 0) send("NUM_SIGN:POS");
                        else if (n.compareTo(BigInteger.ZERO) < 0) send("NUM_SIGN:NEG");
                        else send("NUM_SIGN:ZERO");

                        // OPTIONAL CHECKS (many useful exam helpers) - KEEP COMMENTED BY DEFAULT
                        // If you want more numeric diagnostics, uncomment any of the blocks below.
                        // Each block sends one or more short responses back to the client. Example
                        // usage: remove the surrounding comment markers and recompile.
                        /* ----------------- OPTIONAL NUMERIC HELPERS -----------------
                        // Binary / Hex representation
                        send("BIN_RES:" + n.toString(2));
                        send("HEX_RES:" + n.toString(16));

                        // Even / Odd
                        if (n.mod(BigInteger.valueOf(2)).equals(BigInteger.ZERO)) send("EVENODD:even"); else send("EVENODD:odd");

                        // Divisible by small numbers
                        send("DIV3:" + (n.mod(BigInteger.valueOf(3)).equals(BigInteger.ZERO) ? "yes" : "no"));
                        send("DIV5:" + (n.mod(BigInteger.valueOf(5)).equals(BigInteger.ZERO) ? "yes" : "no"));

                        // Prime check (uses probable prime test)
                        send("IS_PRIME:" + (isPrime(n) ? "yes" : "no"));

                        // Palindrome check (numeric string)
                        String s = n.abs().toString(); send("IS_PALIN:" + (new StringBuilder(s).reverse().toString().equals(s) ? "yes" : "no"));

                        // Digit sum and number of digits
                        send("DIGIT_SUM:" + digitSum(n));
                        send("NUM_DIGITS:" + s.length());

                        // Small combinatorial helpers (commented heavy ops)
                        // Uncomment with caution for big n:
                        // send("FACT:" + factorial(n)); // expensive for large n
                        // send("FIB:" + fibonacci(n.intValue())); // limited to moderate n

                        // GCD example with a fixed value (template to extend)
                        // send("GCD_WITH_1000:" + gcd(n, BigInteger.valueOf(1000)).toString());

                        // END OPTIONAL
                        -------------------------------------------------------------- */

                        return;
                    } catch (Exception ex) {
                        send("NUM_ERR:invalid");
                        return;
                    }
                }

                // Plain text handling (no prefix required)
                // Broadcast the raw message to everyone in the room
                appendChatLog("MSG_FROM:" + username + ":" + body);
                String ts = df.format(new Date());
                broadcast("MSG:" + username + ":" + ts + ":" + body);

                // Also send a compact set of derived string results back to the sender (private feedback)
                // Active string cases: UPPER, REV, COUNT, WCOUNT (uncomment other templates below to enable more)
                send("TEXT_UPPER:" + body.toUpperCase(Locale.ROOT));
                send("TEXT_REV:" + new StringBuilder(body).reverse().toString());
                send("COUNT_RES:" + body.length());
                send("WCOUNT_RES:" + (body.trim().isEmpty()?0:body.trim().split("\\\\s+").length));

                // TEMPLATES (commented): additional string utilities you can enable by uncommenting
                // send("ROT13:" + rot13(body));
                // send("TRIM_RES:" + body.trim());
                // send("SLUG:" + slugify(body));
                // send("NO_VOWELS:" + body.replaceAll("(?i)[aeiou]",""));
                // send("DIGITS_RES:" + body.replaceAll("[^0-9]",""));
                // send("BYTES:" + body.getBytes(StandardCharsets.UTF_8).length);

            } catch (Exception e) {
                send("ERR:Exception " + e.getClass().getSimpleName() + " - " + e.getMessage());
                logConsole("Exception in handler for " + username + ": " + e);
            }
        }

        // Centralized command handler used by both simple and full modes.
        // senderOnly==true => send responses only to the originating client; otherwise broadcast where appropriate.
        private void handleCommand(String prefix, String payload, boolean senderOnly) {
            try {
                switch (prefix) {
                    // ----------------- Messaging -----------------
                    case "MSG": {
                        appendChatLog("MSG_FROM:" + username + ":" + payload);
                        // Plain messages are broadcast to all clients by default.
                        // If a client explicitly used the SERVER: prefix to request a private
                        // server response then that is handled via other commands.
                        String ts = df.format(new Date());
                        broadcast("MSG:" + username + ":" + ts + ":" + payload);
                        break;
                    }

                    // ----------------- Private message example -----------------
                    case "PRIV": {
                        if (!ENABLE_PRIVMSG) { send("ERR:PRIV disabled"); break; }
                        // expected payload: target:message
                        String[] t = payload.split(":",2);
                        if (t.length>=2) {
                            String target = t[0].trim(); String msg = t[1];
                            ClientHandler tgt = findHandler(target);
                            if (tgt!=null) {
                                tgt.send("PRIV:" + username + ":" + msg);
                                send("PRIV_SENT:" + target + ":" + msg);
                                appendChatLog("PRIV_FROM:" + username + ":TO:" + target + ":" + msg);
                            } else send("ERR:User not found");
                        } else send("ERR:PRIV bad args");
                        break;
                    }

                    // ----------------- Numeric example (CALC) -----------------
                    case "CALC": {
                        // CALC:12345 -> compute big integer ^4 and return to sender only
                        try { BigInteger n = new BigInteger(payload.trim()); BigInteger r = n.pow(4); send("CALC_RES:" + r); } catch(Exception ex){ send("CALC_ERR:invalid"); }
                        break;
                    }

                    // ----------------- List / Whoami -----------------
                    case "LIST": {
                        String users = String.join(",", clients.keySet());
                        Map<String,Integer> roomCounts = new HashMap<>();
                        for (String u : rooms.values()) roomCounts.put(u, roomCounts.getOrDefault(u,0)+1);
                        if (senderOnly) send("LIST:" + users + ":ROOMS:" + roomCounts); else broadcast("LIST:" + users + ":ROOMS:" + roomCounts);
                        break;
                    }
                    case "WHOAMI": { send("WHOAMI:" + username + ":rawId:" + (rawId==null?"(none)":rawId) + ":addr:" + socket.getRemoteSocketAddress() + ":room:" + room); break; }

                    // ----------------- File / GETFILE handling already done above; here we only notify -----------------
                    case "SAVELOG": {
                        if (!ENABLE_SAVELOG) { send("ERR:SAVELOG disabled"); break; }
                        for (String h : chatHistory) send("SAVELOG_LINE:" + h);
                        send("SAVELOG_DONE");
                        break;
                    }

                    // ----------------- Bank example (simple) -----------------
                    case "BANK": {
                        if (!ENABLE_BANK) { send("ERR:BANK disabled"); break; }
                        String[] t = payload.split(":",3);
                        if (t.length>=1) {
                            String act = t[0].toUpperCase(Locale.ROOT);
                            accounts.putIfAbsent(username, new BankAccount());
                            BankAccount acc = accounts.get(username);
                            if ("DEPOSIT".equals(act) && t.length>=2) {
                                try { BigInteger amt = new BigInteger(t[1].trim()); BigInteger bal = acc.deposit(amt); appendBankLog(username+":DEPOSIT:"+amt+":BAL="+bal); send("BANK_OK:DEPOSIT:"+bal); } catch(Exception ex){ send("BANK_ERR:bad_amount");}
                            } else if ("WITHDRAW".equals(act) && t.length>=2) {
                                try { BigInteger amt = new BigInteger(t[1].trim()); try { BigInteger bal = acc.withdraw(amt); appendBankLog(username+":WITHDRAW:"+amt+":BAL="+bal); send("BANK_OK:WITHDRAW:"+bal); } catch(InterruptedException ie){ send("BANK_ERR:timeout"); } } catch(Exception ex){ send("BANK_ERR:bad_amount"); }
                            } else if ("BALANCE".equals(act)) {
                                send("BANK_BAL:" + acc.getBalance());
                            } else send("BANK_ERR:unknown");
                        }
                        break;
                    }

                    // ----------------- Poll example -----------------
                    case "POLL": {
                        if (!ENABLE_POLL) { send("ERR:POLL disabled"); break; }
                        String[] t = payload.split(":",3);
                        if (t.length>=1) {
                            String act = t[0].toUpperCase(Locale.ROOT);
                            if ("CREATE".equals(act) && t.length>=3) {
                                int id = ++pollCounter; List<String> opts = Arrays.asList(t[2].split(",")); Poll p = new Poll(id, t[1], opts); polls.put(id, p); broadcast("POLL_NEW:"+id+":"+t[1]+":"+opts); send("POLL_OK:CREATED:"+id);
                            } else if ("VOTE".equals(act) && t.length>=3) {
                                try { int id = Integer.parseInt(t[1]); int idx = Integer.parseInt(t[2]); Poll pol = polls.get(id); if (pol!=null) { pol.vote(idx); broadcast("POLL_RES:"+id+":"+pol.resultString()); send("POLL_OK:VOTED"); } else send("POLL_ERR:unknown"); } catch(Exception ex){ send("POLL_ERR"); }
                            }
                        }
                        break;
                    }

                    case "KICK": {
                        if (!ENABLE_KICK) { send("ERR:KICK disabled"); break; }
                        String who = payload.trim(); if (!username.equals(adminUser)) { send("ERR:Not admin"); } else { ClientHandler ch = findHandler(who); if (ch!=null) { ch.send("SYSTEM:You are kicked by admin"); try { ch.socket.close(); } catch(IOException ignored){} broadcast("SYSTEM:"+who+" was kicked by " + username); } }
                        break;
                    }

                    // ----------------- QUIT handler -----------------
                    case "QUIT": {
                        appendChatLog("QUIT_REQUEST:" + username);
                        // Inform others and close this connection
                        if (BROADCAST_ENABLED) broadcast("SYSTEM:" + username + " is leaving (QUIT)");
                        try { socket.close(); } catch (IOException ignored) {}
                        break;
                    }

                    // ----------------- Numeric auto-handler (NUM) -----------------
                    // When the user types a raw number (e.g., "12345") without a command prefix,
                    // the server will handle it here and return a few helpful numeric views.
                    case "NUM": {
                        try {
                            BigInteger n = new BigInteger(payload.trim());
                            send("CALC_RES:" + n.pow(4));
                            send("BIN_RES:" + n.toString(2));
                            send("HEX_RES:" + n.toString(16));
                        } catch (Exception ex) { send("NUM_ERR:invalid"); }
                        break;
                    }

                    // ----------------- Text helpers (enabled) -----------------
                    case "UPPER": if (payload!=null) { send("TEXT:" + payload.toUpperCase(Locale.ROOT)); } break;
                    case "LOWER": if (payload!=null) { send("TEXT:" + payload.toLowerCase(Locale.ROOT)); } break;
                    case "REV": if (payload!=null) { send("TEXT:" + new StringBuilder(payload).reverse()); } break;
                    case "COUNT": if (payload!=null) { send("COUNT_RES:" + payload.length()); } break;
                    case "WCOUNT": if (payload!=null) { send("WCOUNT_RES:" + (payload.trim().isEmpty()?0:payload.trim().split("\\s+").length)); } break;

                    default: {
                        // Most of the original extended examples (string transforms, numeric helpers, etc.)
                        // were part of a large template. To keep the server code short and predictable for testing
                        // we handle a small set of concrete examples above (UPPER/LOWER/REV/COUNT/BANK/POLL/CALC/PRIV/LIST).
                        // You can re-enable other utility cases by uncommenting the templates below.

                        // Example template (uncomment to enable):
                        // case "UPPER": if (payload!=null) send("TEXT:" + payload.toUpperCase(Locale.ROOT)); break;
                        // case "LOWER": if (payload!=null) send("TEXT:" + payload.toLowerCase(Locale.ROOT)); break;
                        // case "REV": if (payload!=null) send("TEXT:" + new StringBuilder(payload).reverse()); break;
                        // case "COUNT": if (payload!=null) send("COUNT_RES:" + payload.length()); break;
                        // ... (many more templates omitted here - uncomment as required)

                        // Fallback behavior: if senderOnly, reply privately; otherwise broadcast as system message
                        if (senderOnly) send("SRV_RES:Unhandled -> " + (payload==null?prefix:payload)); else broadcast("SYSTEM:Unhandled command: " + prefix + " payload=" + payload);
                        break;
                    }
                }
            } catch (Exception ex) { send("ERR:ExceptionInCmd " + ex.getMessage()); }
        }
    }

    public static void main(String[] args) {
        int port = 5000;
        Server server = new Server(port);
        server.initializeGUI();
        SwingUtilities.invokeLater(() -> {
            server.startServer();
            server.appendConsole("(Auto-start) Server started on port " + port);
        });
    }

    // ---------- Numeric helper implementations (small, safe) ----------
    // These helpers are active and available for the optional templates above.
    private static boolean isPrime(BigInteger n) {
        if (n.compareTo(BigInteger.TWO) < 0) return false;
        // Use BigInteger's built-in probable prime with high certainty
        return n.isProbablePrime(20);
    }

    private static int digitSum(BigInteger n) {
        String s = n.abs().toString(); int sum = 0; for (int i = 0; i < s.length(); i++) sum += s.charAt(i) - '0'; return sum;
    }

    // (Optional heavy utilities - keep commented unless needed)
    /*
    private static BigInteger factorial(BigInteger n) {
        BigInteger r = BigInteger.ONE;
        for (BigInteger i = BigInteger.ONE; i.compareTo(n) <= 0; i = i.add(BigInteger.ONE)) r = r.multiply(i);
        return r;
    }

    private static BigInteger gcd(BigInteger a, BigInteger b) { return a.gcd(b); }

    private static BigInteger fibonacci(int k) { if (k < 0) return BigInteger.ZERO; BigInteger a = BigInteger.ZERO, b = BigInteger.ONE; for (int i = 0; i < k; i++) { BigInteger t = a.add(b); a = b; b = t; } return a; }
    */
}
