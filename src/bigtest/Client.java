package bigtest;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.nio.file.Files;
import javax.net.ssl.*;

/*
 * bigtest.Client - compact client with GUI, bank, poll, file, typing, AUTH/SSL optional
 */
public class Client {
    // ----------------- OPTIONAL CLIENT AUTH -----------------
    private static final boolean ENABLE_AUTH_CLIENT = false; // set true to enable
    // When REQUIRE_AUTH_CLIENT_DIALOG == false, the client will not show the GUI login dialog
    // and will use AUTO_CONNECT_* values below. This is useful for automated testing.
    private static final boolean REQUIRE_AUTH_CLIENT_DIALOG = false; // set false to skip dialog and auto-connect
    private static final String AUTO_CONNECT_HOST = "localhost";
    private static final int AUTO_CONNECT_PORT = 5000;
    private static final String AUTO_CONNECT_ID = "20520001"; // change or set to "" to prompt
    // Optional predefined password (used when PREDEFINED_ID_MODE == true and server requires AUTH)
    // If empty, client will prompt for password when server requests AUTH.
    @SuppressWarnings("unused")
    private static final String PREDEFINED_PASS = "pass"; // set to "" to require prompt
    // --------------------------------------------------------
    // ----------------- OPTIONAL SSL CLIENT -----------------
    private static final boolean ENABLE_SSL_CLIENT = false; // set true to use SSLSocket
    private static final String SSL_TRUSTSTORE = "./client_truststore.jks";
    private static final String SSL_TRUSTSTORE_PASS = "123456";
    // --------------------------------------------------------

    private JFrame frame;
    private JTextPane chatPane;
    private DefaultListModel<String> onlineModel;
    private JList<String> onlineList;
    private JLabel onlineCountLabel; // shows number of online users + simple room summary
    private JPanel leftPanel; // left panel for online list (titled border shows count)
    private JTextField inputField;
    private JButton sendBtn, fileBtn, saveLogBtn;
    // typing indicator removed per request but keep fields declared to avoid missing-symbol errors
    private JLabel typingLabel;
    private javax.swing.Timer typingTimer; // kept for optional re-enable; may be null in simple-mode
    private boolean typingOn = false;
    // Bank UI
    private JLabel balLabel;
    private DefaultListModel<String> bankHistoryModel;
    private JList<String> bankHistoryList;
    // Files UI + chunk upload toggles
    private DefaultListModel<String> fileListModel;
    private JList<String> fileList;
    private static final boolean CHUNK_UPLOAD_ENABLED = true; // toggle chunked upload in client
    private static final int CHUNK_SIZE = 60_000; // chunk size in bytes

    // Poll UI
    private DefaultListModel<String> pollListModel;
    private JList<String> pollList;
    private JTextArea pollDetails;
    // Local poll store: id -> PollData (title + options + lastResult)
    private final Map<Integer, PollData> pollsMap = new HashMap<>();

    // Network
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private String username;
    private String clientPassword; // set when using AUTH
    // store server connection info so we can reconnect
    private String serverHost;
    private int serverPort;
    @SuppressWarnings("unused")
    private volatile boolean connected = false;

    // Simple container for poll info on client side
    private static class PollData {
        final int id; final String title; final List<String> options; String lastResult = "";
        PollData(int id, String title, List<String> opts) { this.id = id; this.title = title; this.options = new ArrayList<>(opts); }
    }

    public Client() {
        // sanitize clipboard to avoid IntelliJ-specific serialized objects that cause
        // ClassNotFoundException when DataFlavor is constructed (seen in some JDK/IDE combos).
        try { sanitizeClipboard(); } catch (Throwable ignored) {}
        buildGUI();
    }

    // Sanitize system clipboard: remove any serialized-object DataFlavor entries that
    // reference IDE/editor internal classes (e.g. com.intellij.openapi.editor.impl.*).
    // This is conservative: we only clear problematic transferable flavors to avoid
    // ClassNotFoundException reported by the user environment when inspecting clipboard.
    private void sanitizeClipboard() {
        try {
            java.awt.datatransfer.Clipboard cb = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
            java.awt.datatransfer.Transferable t = cb.getContents(null);
            if (t == null) return;
            java.awt.datatransfer.DataFlavor[] flavors = t.getTransferDataFlavors();
            boolean unsafe = false;
            for (java.awt.datatransfer.DataFlavor f : flavors) {
                try {
                    Class<?> cls = f.getRepresentationClass();
                    if (cls != null && cls.getName().contains("com.intellij")) unsafe = true;
                } catch (Throwable ex) {
                    // If we can't resolve the flavor class, treat clipboard as unsafe.
                    unsafe = true;
                }
            }
            if (unsafe) {
                // replace clipboard contents with plain text fallback (if available) or empty string
                try {
                    Object o = null;
                    try { o = t.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor); } catch (Throwable ex) { o = null; }
                    String txt = o instanceof String ? (String) o : "";
                    try { cb.setContents(new java.awt.datatransfer.StringSelection(txt), null); } catch (IllegalStateException ise) { /* clipboard not available; ignore */ }
                } catch (Throwable ex) {
                    // last-resort: try set empty
                    try { cb.setContents(new java.awt.datatransfer.StringSelection(""), null); } catch (Throwable ignore) {}
                }
            }
        } catch (Throwable ignore) {
            // If clipboard operations fail, we don't want to crash the client; proceed normally.
        }
    }

    // Build GUI components and wire events
    private void buildGUI() {
        // Set global font to Times New Roman for requested look
        Font tr = new Font("Times New Roman", Font.PLAIN, 14);
        UIManager.put("Label.font", tr);
        UIManager.put("Button.font", tr);
        UIManager.put("TextField.font", tr);
        UIManager.put("TextArea.font", tr);
        UIManager.put("List.font", tr);

        frame = new JFrame("NPR Chat - Client");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(900, 650);

        // Left: online panel with count + list (pink background)
        onlineModel = new DefaultListModel<>();
        onlineList = new JList<>(onlineModel);
        onlineList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        onlineCountLabel = new JLabel("Online: 0");
        onlineCountLabel.setFont(new Font("Times New Roman", Font.BOLD, 14));
        leftPanel = new JPanel(new BorderLayout());
        leftPanel.setBackground(new Color(255,192,203));
        leftPanel.setBorder(BorderFactory.createTitledBorder("Online (0)"));
        leftPanel.add(onlineCountLabel, BorderLayout.NORTH);
        JScrollPane leftScroll = new JScrollPane(onlineList); leftScroll.getViewport().setBackground(new Color(255,240,245));
        leftPanel.add(leftScroll, BorderLayout.CENTER);

        // Double-click to prepare private message
        onlineList.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    String target = onlineList.getSelectedValue();
                    if (target != null) {
                        inputField.setText("PRIV:" + target + ":");
                        inputField.requestFocus();
                    }
                }
            }
        });

        // Right: tabs
        JTabbedPane tabs = new JTabbedPane();

        // Chat tab
        JPanel chatTab = new JPanel(new BorderLayout());
        chatPane = new JTextPane();
        chatPane.setEditable(false);
        chatPane.setBackground(new Color(255, 240, 245)); // pink-ish
        chatPane.setFont(new Font("Times New Roman", Font.PLAIN, 14));
        // File notifications list below the chat area
        fileListModel = new DefaultListModel<>(); fileList = new JList<>(fileListModel);
        fileList.setFont(new Font("Times New Roman", Font.PLAIN, 13));
        fileList.setBackground(new Color(255,240,245));
        fileList.setBorder(BorderFactory.createTitledBorder("Files (double-click to download)"));
        // Double-click to request file download from server
        fileList.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    String sel = fileList.getSelectedValue();
                    if (sel != null) {
                        String name = sel.contains(":") ? sel.substring(sel.lastIndexOf(":" ) + 1) : sel;
                        sendRaw("GETFILE:" + name);
                        appendSystem("Requested file from server: " + name);
                    }
                }
            }
        });
        JSplitPane chatSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(chatPane), new JScrollPane(fileList));
        chatSplit.setDividerLocation(420);
        chatTab.add(chatSplit, BorderLayout.CENTER);

        JPanel inputPanel = new JPanel(new BorderLayout());
        inputField = new JTextField();
        sendBtn = new JButton("Send");
        fileBtn = new JButton("Send File");
        saveLogBtn = new JButton("Save Log");
        typingLabel = new JLabel(" ");

        JPanel rightButtons = new JPanel(new FlowLayout());
        rightButtons.setBackground(new Color(255,192,203));
        rightButtons.add(fileBtn);
        rightButtons.add(saveLogBtn);
        rightButtons.add(sendBtn);

        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(rightButtons, BorderLayout.EAST);
        chatTab.add(inputPanel, BorderLayout.SOUTH);
        chatTab.add(typingLabel, BorderLayout.NORTH);

        tabs.addTab("Chat", chatTab);

        // Bank tab (must be present as per spec)
        JPanel bankTab = new JPanel(new BorderLayout()); bankTab.setBackground(new Color(255,240,245));
        JPanel bankTop = new JPanel(new FlowLayout(FlowLayout.LEFT));
        balLabel = new JLabel("Balance: 0"); balLabel.setFont(new Font("Times New Roman", Font.BOLD, 16));
        bankTop.add(balLabel);
        JTextField amtField = new JTextField(10);
        bankTop.add(new JLabel("Amount:")); bankTop.add(amtField);
        JButton depBtn = new JButton("Deposit");
        JButton witBtn = new JButton("Withdraw");
        bankTop.add(depBtn); bankTop.add(witBtn);
        bankTab.add(bankTop, BorderLayout.NORTH);
        bankHistoryModel = new DefaultListModel<>(); bankHistoryList = new JList<>(bankHistoryModel);
        bankTab.add(new JScrollPane(bankHistoryList), BorderLayout.CENTER);
        tabs.addTab("Bank", bankTab);

        // Poll tab
        JPanel pollTab = new JPanel(new BorderLayout()); pollTab.setBackground(new Color(255,240,245));
        pollListModel = new DefaultListModel<>(); pollList = new JList<>(pollListModel);
        JScrollPane pollListScroll = new JScrollPane(pollList); pollListScroll.getViewport().setBackground(new Color(255,240,245));
        pollTab.add(pollListScroll, BorderLayout.WEST);
        pollDetails = new JTextArea(); pollDetails.setEditable(false); pollDetails.setBackground(new Color(255,240,245)); pollDetails.setFont(new Font("Times New Roman", Font.PLAIN, 14));
        pollTab.add(new JScrollPane(pollDetails), BorderLayout.CENTER);
        JPanel pollControl = new JPanel(new GridLayout(0,1));
        JTextField pollTitle = new JTextField(); JTextField pollOpts = new JTextField();
        JButton createPollBtn = new JButton("Create Poll"); JButton voteBtn = new JButton("Vote");
        pollControl.add(new JLabel("Title:")); pollControl.add(pollTitle); pollControl.add(new JLabel("Options (comma):")); pollControl.add(pollOpts);
        pollControl.add(createPollBtn); pollControl.add(voteBtn);
        pollTab.add(pollControl, BorderLayout.EAST);
        tabs.addTab("Poll", pollTab);

        // Layout split
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, tabs);
        split.setDividerLocation(220);
        frame.getContentPane().add(split);

        // Pink background
        frame.getContentPane().setBackground(new Color(255, 192, 203));

        // Event wiring
        sendBtn.addActionListener(_e -> sendInput());
        inputField.addActionListener(_e -> sendInput());

        // typing indicator removed: input field sends only normal messages
        typingTimer = null; typingOn = false;

        // File send
        fileBtn.addActionListener(_e -> {
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                File f = fc.getSelectedFile(); sendFile(f);
            }
        });

        // OPTIONAL: Paste absolute path instead of JFileChooser
        // Moved into the input area so it's next to the message input
        JTextField pathField = new JTextField(24);
        JButton sendPathBtn = new JButton("Send Path");
        JPanel pathPanel = new JPanel(new FlowLayout(FlowLayout.LEFT)); pathPanel.setBackground(new Color(255,240,245));
        pathPanel.add(new JLabel("File path:")); pathPanel.add(pathField); pathPanel.add(sendPathBtn);
        // add pathPanel into the bottom input area (above inputField)
        inputPanel.add(pathPanel, BorderLayout.NORTH);
        sendPathBtn.addActionListener(_ev -> { String p = pathField.getText().trim(); if (!p.isEmpty()) sendFile(new File(p)); });
        saveLogBtn.addActionListener(_e -> sendRaw("SAVELOG"));

        // Bank actions
        depBtn.addActionListener(_e -> {
            String v = amtField.getText().trim(); if (!v.isEmpty()) sendRaw("BANK:DEPOSIT:" + v);
        });
        witBtn.addActionListener(_e -> { String v = amtField.getText().trim(); if (!v.isEmpty()) sendRaw("BANK:WITHDRAW:" + v); });

        // Poll actions
        createPollBtn.addActionListener(_e -> {
            String t = pollTitle.getText().trim(); String o = pollOpts.getText().trim(); if (!t.isEmpty() && !o.isEmpty()) sendRaw("POLL:CREATE:" + t + ":" + o);
        });
        voteBtn.addActionListener(_e -> {
            String sel = pollList.getSelectedValue(); if (sel==null) { JOptionPane.showMessageDialog(frame, "Select a poll first"); return; }
            String[] parts = sel.split(":",2); int pid = Integer.parseInt(parts[0].trim());
            PollData pd = pollsMap.get(pid); if (pd==null) { JOptionPane.showMessageDialog(frame, "Poll details not loaded yet"); return; }
            Object choice = JOptionPane.showInputDialog(frame, "Choose option:", "Vote - " + pd.title, JOptionPane.PLAIN_MESSAGE, null, pd.options.toArray(), pd.options.get(0));
            if (choice != null) {
                int idx = pd.options.indexOf(choice.toString()); if (idx>=0) sendRaw("POLL:VOTE:" + pid + ":" + idx);
            }
        });

        // Poll selection shows details
        pollList.addListSelectionListener(e -> {
            String sel = pollList.getSelectedValue(); if (sel==null) { pollDetails.setText(""); return; }
            String[] parts = sel.split(":",2); int pid = Integer.parseInt(parts[0].trim());
            PollData pd = pollsMap.get(pid);
            if (pd!=null) {
                StringBuilder sb = new StringBuilder(); sb.append(pd.title).append("\n");
                for (int i=0;i<pd.options.size();i++) sb.append(i).append(": ").append(pd.options.get(i)).append('\n');
                if (pd.lastResult!=null && !pd.lastResult.isEmpty()) sb.append("\nResults: ").append(pd.lastResult);
                pollDetails.setText(sb.toString());
            } else pollDetails.setText("(details loading)");
        });

        // (Reconnect UI removed) — frame visible now
        frame.setVisible(true);
        // Start in disconnected state until connect() is called
        // Do not append an initial "Not connected" message during GUI startup.
        // Disable controls silently until a connection is established.
        if (sendBtn != null) sendBtn.setEnabled(false);
        if (inputField != null) inputField.setEnabled(false);
        if (fileBtn != null) fileBtn.setEnabled(false);
        if (saveLogBtn != null) saveLogBtn.setEnabled(false);

        // OPTIONAL: Quit button (commented). Uncomment to add a dedicated Quit button that sends QUIT to server and closes client.
        /*
        JButton quitBtn = new JButton("Quit");
        quitBtn.addActionListener(ev -> {
            sendRaw("QUIT");
            try { if (socket != null) socket.close(); } catch(IOException ignored) {}
            frame.dispose();
            System.exit(0);
        });
        // Add quitBtn to rightButtons if you uncomment above
        */
    }

    // Connect to server and start reader
    public boolean connect(String host, int port, String id) {
        // Close previous socket if any
        try { if (socket != null && !socket.isClosed()) socket.close(); } catch (IOException ignored) {}
        List<String> errors = new ArrayList<>();
        String msg = null; // keeps last error message available to UI/dialogs
        try {
            appendSystem("Resolving host '" + host + "'...");
            int timeout = 4000;
            socket = createSocket(host, port, timeout, errors);
             in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            this.username = id;
            this.serverHost = host;
            this.serverPort = port;
            // Do NOT mark connected true or enable UI until handshake/auth completes successfully.
            connected = false;
            // New handshake: send ID first. If server requires AUTH it will reply with "AUTH_REQ".
            // This avoids mismatch when server's Require AUTH toggle is off.
            out.println("ID:" + id);
            try {
                // set a short socket read timeout to peek server response (AUTH_REQ or immediate replies)
                socket.setSoTimeout(3000);
                String firstResp = in.readLine();
                // clear timeout
                socket.setSoTimeout(0);

                // If server demands AUTH, handle auth flow; otherwise process any immediate server message.
                if (firstResp != null && firstResp.equals("AUTH_REQ")) {
                    // server requires auth - handle several modes
                    if (!ENABLE_AUTH_CLIENT) {
                        if (!REQUIRE_AUTH_CLIENT_DIALOG && PREDEFINED_PASS != null && !PREDEFINED_PASS.isEmpty()) {
                            clientPassword = PREDEFINED_PASS;
                            appendSystem("Auto-sending predefined password for " + id);
                            sendRaw("AUTH:" + id + ":" + clientPassword);
                        } else {
                            handleDisconnect("Server requires AUTH but client AUTH is disabled");
                            return false;
                        }
                    } else {
                        if (clientPassword == null) {
                            clientPassword = JOptionPane.showInputDialog(frame, "Enter password for " + id + ":");
                            if (clientPassword == null) { handleDisconnect("Auth cancelled"); return false; }
                        }
                        sendRaw("AUTH:" + id + ":" + clientPassword);
                    }

                    // Wait briefly for AUTH_OK or other responses
                    boolean authOk = false;
                    long deadline = System.currentTimeMillis() + 5000;
                    while (System.currentTimeMillis() < deadline) {
                        String authResp = null;
                        try { authResp = in.readLine(); } catch (IOException ioe) { break; }
                        if (authResp == null) break;
                        String ar = authResp.trim();
                        if (ar.equals("AUTH_OK")) { authOk = true; appendSystem("Authenticated successfully"); break; }
                        if (ar.startsWith("AUTH_FAIL")) { handleDisconnect("Auth failed: " + ar); return false; }
                        if (ar.startsWith("ID_OK") || ar.startsWith("ID_RES")) { authOk = true; handleServerLine(authResp); break; }
                        handleServerLine(authResp);
                    }
                    if (!authOk) { handleDisconnect("Auth failed: no AUTH_OK received"); return false; }
                } else {
                    // No AUTH_REQ: process firstResp if present (e.g., ID_OK)
                    if (firstResp != null && !firstResp.trim().isEmpty()) handleServerLine(firstResp);
                }

                // Handshake complete — mark connected and enable UI
                connected = true; setConnectedUI(true);
                 } catch (java.net.SocketTimeoutException ste) {
                 // no immediate response - proceed; reset timeout
                 try { socket.setSoTimeout(0); } catch (Exception ignored) {}
             }

             // Reader thread
             new Thread(() -> {
                 try {
                     String line;
                     while ((line = in.readLine()) != null) handleServerLine(line);
                 } catch (IOException e) { handleDisconnect("Disconnected: " + e.getClass().getSimpleName() + ": " + e.getMessage()); }
             }).start();

             return true;
        } catch (Exception e) {
            msg = e.getClass().getSimpleName() + ": " + e.getMessage();
            appendSystem("Connect failed: " + msg);
            // Automatic fallback: try localhost loopback if initial resolution failed
            try {
                appendSystem("Attempting fallback to 127.0.0.1:" + port);
                java.net.Socket f = new java.net.Socket();
                f.connect(new java.net.InetSocketAddress("127.0.0.1", port), 3000);
                socket = f;
                in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                this.username = id;
                this.serverHost = "127.0.0.1";
                this.serverPort = port;
                connected = true;
                setConnectedUI(true);
                out.println("ID:" + id);
                new Thread(() -> {
                    try { String line; while ((line = in.readLine()) != null) handleServerLine(line); } catch (IOException ex) { handleDisconnect("Disconnected: " + ex.getClass().getSimpleName() + ": " + ex.getMessage()); }
                }).start();
                appendSystem("Fallback to 127.0.0.1 succeeded");
                return true;
            } catch (Exception fallbackEx) {
                appendSystem("Fallback failed: " + fallbackEx.getClass().getSimpleName() + ": " + fallbackEx.getMessage());
            }
        }
        // show dialog with options to edit/retry
        final String dialogMsg = msg == null ? "(no details)" : msg; // capture for lambda (must be effectively final)
        SwingUtilities.invokeLater(() -> {
             Object[] opts = new Object[] {"Retry", "Edit host/port", "Cancel"};
             int sel = JOptionPane.showOptionDialog(frame, "Failed to connect to " + host + ":" + port + "\n" + dialogMsg + "\nChoose action:", "Connection failed", JOptionPane.DEFAULT_OPTION, JOptionPane.ERROR_MESSAGE, null, opts, opts[0]);
             if (sel == 0) {
                // Retry with same host/port
                connect(serverHost, serverPort, username);
            } else if (sel == 1) {
                // Let user enter new host/port
                String nh = JOptionPane.showInputDialog(frame, "Server host:", serverHost==null?"localhost":serverHost);
                if (nh != null && !nh.trim().isEmpty()) {
                    String np = JOptionPane.showInputDialog(frame, "Server port:", serverPort>0?Integer.toString(serverPort):"5000");
                    try { int pn = Integer.parseInt(np.trim()); serverHost = nh.trim(); serverPort = pn; connect(serverHost, serverPort, username); } catch (Exception ex) { appendSystem("Bad port entered"); }
                }
            }
        });
        // ensure UI allows retry (no reconnect button)
        setConnectedUI(false);
         return false;
     }

    // Handle disconnect logic in one place
    private void handleDisconnect(String reason) {
        connected = false;
        // reconnect feature removed: update UI and close socket
        setConnectedUI(false);
        try { if (socket != null) socket.close(); } catch (IOException e) { appendSystem("Error closing socket: " + e.getMessage()); }
        appendSystem(reason);
    }

    // Enable/disable primary UI elements depending on connection state
    private void setConnectedUI(boolean on) {
        SwingUtilities.invokeLater(() -> {
            boolean prev = connected;
            connected = on;
            if (sendBtn != null) sendBtn.setEnabled(on);
            if (inputField != null) inputField.setEnabled(on);
            if (fileBtn != null) fileBtn.setEnabled(on);
            if (saveLogBtn != null) saveLogBtn.setEnabled(on);
            // Only append status on transitions to connected OR when there is an explicit error message
            if (prev != on && on) {
                appendSystem("Connected to " + serverHost + ":" + serverPort);
            }
        });
    }

    // Send input field content
    private void sendInput() {
        String t = inputField.getText().trim(); if (t.isEmpty()) return;
        // Send plain user input. Server auto-detects numeric or command tokens when possible.
        sendRaw(t);
        inputField.setText("");
    }

    /*
     * ========== CLIENT GUIDE: private messages, broadcast, and binary file transfer ==========
     *
     * 1) How to send a private message from the client:
     *    - Option A (prefix style): type `PRIV:targetUser:hello` into the input field and press Send.
     *      The server already understands `PRIV:` messages and will forward to the target only.
     *    - Option B (at-style quick PM): type `@targetUser hello` and uncomment the server-side
     *      UNCOMMENT_PRIV_TEMPLATE (see Server.java) to enable parsing of `@target` syntax.
     *
     *    Client-side: no code change required — simply type `PRIV:target:message`.
     *
     * 2) How to switch client behavior between 'send to server only' and 'broadcast'
     *    - Client is a dumb terminal of user input: it sends the raw text to server over out.println(...).
     *    - Server decides routing (unicast vs broadcast). To change server routing, find the
     *      UNCOMMENT_BROADCAST_TEMPLATE block in Server.java and uncomment/modify as desired.
     *    - Client-side changes: none required. The client will receive either a direct reply or
     *      broadcast messages from server and display them.
     *
     * 3) How to enable more robust file transfer (binary, using DataOutputStream):
     *    - Current behavior: client sends base64 payloads over text lines (FILEDATA/FILECHUNK).
     *      This works but is less efficient for large files.
     *    - To switch to binary streaming, you must change both client and server. Steps:
     *      a) Client: replace sendFile(File f) with the `sendFileBinary(File f)` template below and
     *         use DataOutputStream to write a header via writeUTF("BFILE:<name>:<length>") followed
     *         by raw bytes.
     *      b) Server: uncomment the UNCOMMENT_BINARY_SERVER_TEMPLATE in Server.java which uses
     *         DataInputStream and reads UTF header then raw bytes.
     *      c) Recompile both client and server.
     *
     *    Client-side binary template (paste into this file and use instead of sendFile):
     *    // UNCOMMENT_BINARY_CLIENT_TEMPLATE START
     *    // private void sendFileBinary(File f) {
     *    //     try (DataOutputStream dos = new DataOutputStream(socket.getOutputStream()); FileInputStream fis = new FileInputStream(f)) {
     *    //         byte[] buf = new byte[8192]; long len = f.length(); dos.writeUTF("BFILE:" + f.getName() + ":" + len);
     *    //         int r; while ((r = fis.read(buf)) > 0) dos.write(buf, 0, r); dos.flush();
     *    //         appendSystem("Binary file sent: " + f.getAbsolutePath());
     *    //     } catch (Exception ex) { appendSystem("Binary send failed: " + ex.getMessage()); }
     *    // }
     *    // UNCOMMENT_BINARY_CLIENT_TEMPLATE END
     *
     *    Note: both client and server must use binary template simultaneously. Do not mix base64 and binary
     *    modes for the same transfer.
     *
     * 4) Quick notes on 'what to uncomment' and when:
     *    - To enable server-side @target quick private messaging: uncomment UNCOMMENT_PRIV_TEMPLATE in Server.java.
     *    - To make server send only to the sender (server-only mode): uncomment UNCOMMENT_SERVER_ONLY_TEMPLATE in Server.java and comment out the broadcast("MSG:...") line.
     *    - To adopt binary file transfer: uncomment UNCOMMENT_BINARY_CLIENT_TEMPLATE in Client.java AND UNCOMMENT_BINARY_SERVER_TEMPLATE in Server.java.
     *    - After any uncommenting: recompile both files.
     */
    // Generic send
    private void sendRaw(String line) { if (out != null) out.println(line); }

    // Send file as base64 (simple)
    private void sendFile(File f) {
         try {
            String absPath = f.getAbsolutePath();
            String name = f.getName();
            // Send only the filename (not the full absolute path) to avoid ':' from Windows drive letters
            // which would break naive colon-splitting on the server side.
            sendRaw("FILE:" + name);
             byte[] data = Files.readAllBytes(f.toPath());
             if (CHUNK_UPLOAD_ENABLED && data.length > CHUNK_SIZE) {
                 int idx = 0, off = 0, total = data.length;
                 int chunks = (total + CHUNK_SIZE - 1) / CHUNK_SIZE;
                 appendSystem("Uploading in " + chunks + " chunks...");
                 while (off < total) {
                     int len = Math.min(CHUNK_SIZE, total - off);
                     byte[] part = Arrays.copyOfRange(data, off, off + len);
                     String b64 = Base64.getEncoder().encodeToString(part);
                     // ensure no stray backslashes in Base64 (JDK bug workaround)
                     b64 = b64.replaceAll("\\\\", "");
                     // send chunk identified by filename to avoid colon issues with Windows absolute paths
                     sendRaw("FILECHUNK:" + name + ":" + idx + ":" + b64);
                     off += len; idx++;
                     Thread.sleep(40);
                 }
                 // signal end using filename only
                 sendRaw("FILEEND:" + name);
                 appendSystem("Chunked upload finished: " + f.getAbsolutePath());
             } else {
                 String b64 = Base64.getEncoder().encodeToString(data);
                 Thread.sleep(80);
                 // send single-packet file using filename only
                 sendRaw("FILEDATA:" + name + ":" + b64);
                 appendSystem("File sent (single-pkt): " + f.getAbsolutePath());
             }
         } catch (Exception e) { appendSystem("File send error: " + e.getMessage()); }
     }

    // Handle lines from server
    private void handleServerLine(String line) {
         String[] p = line.split(":",4);
         String prefix = p[0];
        // Minimal handlers for simple-mode testing. Commented code below shows other handlers you can re-enable later.
        switch (prefix) {
            case "ID_OK": // server confirmed username
                if (p.length>=2) { username = p[1]; appendSystem("ID_OK:" + username); }
                break;
            case "ID_RES": if (p.length>=2) appendSystem("ID_RES:" + p[1]); break;
            case "CALC_RES": if (p.length>=2) appendSystem("CALC_RES: " + p[1]); break;
            case "BIN_RES": if (p.length>=2) appendSystem("BIN: " + p[1]); break;
            case "HEX_RES": if (p.length>=2) appendSystem("HEX: " + p[1]); break;
            case "TEXT_UPPER": if (p.length>=2) appendStyled("[UPPER] " + p[1] + "\n", Color.MAGENTA, true); break;
            case "TEXT_REV": if (p.length>=2) appendStyled("[REV] " + p[1] + "\n", new Color(128,0,128), false); break;
            case "COUNT_RES": if (p.length>=2) appendStyled("[COUNT] " + p[1] + "\n", Color.GRAY, false); break;
            case "WCOUNT_RES": if (p.length>=2) appendStyled("[WCOUNT] " + p[1] + "\n", Color.GRAY, false); break;
            case "FILE_BC": // FILE_BC:user:name
                if (p.length>=3) { String sender = p[1]; String name = p[2]; SwingUtilities.invokeLater(() -> fileListModel.addElement(sender + ":" + name)); appendSystem("File available: " + sender + ":" + name); }
                break;
            case "MSG": // MSG:user:ts:msg
                if (p.length>=4) appendChat(p[1], p[3]);
                break;
            case "SYSTEM": appendSystem(p.length>=2?p[1]:line); break;
            case "SRV_RES": // server simple-mode response
                appendSystem(p.length>=2?p[1]:line);
                break;
            case "LIST": // LIST:<usersCSV>:ROOMS:<roomMap>
                if (p.length>=2) { String users = p[1]; String roomMap = p.length>=4?p[3]:""; updateOnline(users, roomMap); }
                break;
            case "READY": appendSystem("Server ready for file: " + (p.length>=2?p[1]:"")); break;
            case "FILE_OK": // FILE_OK:<nameOnly>:SAVED_AT:<absPath>
                if (line.startsWith("FILE_OK:")) {
                    int idx = line.indexOf(":SAVED_AT:");
                    if (idx > 0) {
                        String nameOnly = line.substring(8, idx);
                        String saved = line.substring(idx + ":SAVED_AT:".length());
                        appendSystem("File uploaded and saved on server: " + nameOnly + " -> " + saved);
                    }
                }
                break;
            case "FILE_SEND": // FILE_SEND:<name>:<b64>
                if (p.length >= 3) {
                    String name = p[1]; String b64 = p[2];
                    byte[] bytes = Base64.getDecoder().decode(b64);
                    SwingUtilities.invokeLater(() -> {
                        JFileChooser fc = new JFileChooser(); fc.setSelectedFile(new File(name));
                        if (fc.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
                            try (FileOutputStream fos = new FileOutputStream(fc.getSelectedFile())) { fos.write(bytes); appendSystem("File downloaded: " + fc.getSelectedFile().getAbsolutePath()); } catch (IOException ex) { appendSystem("Save failed: " + ex.getMessage()); }
                        } else appendSystem("Download cancelled");
                    });
                }
                break;
            case "FILECHUNK_OK": if (p.length>=3) appendSystem("Chunk ack: " + p[1] + " idx=" + p[2]); break;
            default: appendSystem(line); break;
        }
    }

    // Helpers to append different styled messages
    private void appendChat(String user, String msg) { appendStyled(user + ": ", Color.BLUE, true); appendStyled(msg + "\n", Color.BLACK, false); }
    private void appendPrivate(String from, String msg) { appendStyled("[PRIVATE] " + from + ": ", new Color(128,0,128), true); appendStyled(msg + "\n", new Color(128,0,128), false); }
    private void appendShout(String user, String msg) { appendStyled("[SHOUT] " + user + ": ", Color.RED, true); appendStyled(msg + "\n", Color.RED, false); }
    // Internal system append implementation
    private void appendSystemInternal(String s) { appendStyled("[SYSTEM] " + s + "\n", Color.DARK_GRAY, false); }
    // Public wrapper used across the client
    private void appendSystem(String s) { appendSystemInternal(s); }
    private void appendBank(String s) { appendStyled("[BANK] " + s + "\n", new Color(0,128,0), false); }
    // ensure bank history list visible
    private void appendBankHistory(String s) { SwingUtilities.invokeLater(() -> bankHistoryModel.addElement(s)); }

    private void appendStyled(String text, Color color, boolean bold) {
        SwingUtilities.invokeLater(() -> {
            StyledDocument doc = chatPane.getStyledDocument(); Style style = chatPane.addStyle("s", null);
            StyleConstants.setForeground(style, color); StyleConstants.setFontFamily(style, "Times New Roman"); StyleConstants.setFontSize(style, 14); StyleConstants.setBold(style, bold);
            try { doc.insertString(doc.getLength(), text, style); } catch (BadLocationException e) { appendSystem("UI append error: " + e.getMessage()); }
        });
    }

    // Update online list and count; roomMap is a simple toString from server
    private void updateOnline(String csv, String roomMap) {
        SwingUtilities.invokeLater(() -> {
            onlineModel.clear();
            if (csv==null||csv.isEmpty()) {
                onlineCountLabel.setText("Online: 0");
                if (leftPanel!=null) leftPanel.setBorder(BorderFactory.createTitledBorder("Online (0)"));
                return;
            }
            String[] users = csv.split(",");
            for (String s: users) if (!s.isEmpty()) onlineModel.addElement(s);
            onlineCountLabel.setText("Online: " + users.length + (roomMap!=null && !roomMap.isEmpty() ? " | Rooms: " + roomMap : ""));
            if (leftPanel!=null) leftPanel.setBorder(BorderFactory.createTitledBorder("Online (" + users.length + ") " + (roomMap!=null && !roomMap.isEmpty() ? roomMap : "")));
        });
    }


    // Entry point
    public static void main(String[] args) {
        // Clear the system clipboard as early as possible to avoid JVM attempting to
        // construct DataFlavors that reference IDE classes (which causes
        // ClassNotFoundException messages). This is a pragmatic startup workaround.
        try {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                new java.awt.datatransfer.StringSelection(""), null);
        } catch (Throwable ignore) {
            // If clipboard is unavailable, ignore — app will still continue.
        }

        SwingUtilities.invokeLater(() -> {
            Client c = new Client();
            String host, portStr, id; boolean ok;
            // Login/connect flow options:
            // - If REQUIRE_AUTH_CLIENT_DIALOG == true -> show dialog (default in interactive use)
            // - If REQUIRE_AUTH_CLIENT_DIALOG == false -> use AUTO_CONNECT_* values (no dialog) for quick testing
            if (REQUIRE_AUTH_CLIENT_DIALOG && ENABLE_AUTH_CLIENT) {
                String[] auth = c.showAuthDialog();
                if (auth == null) { JOptionPane.showMessageDialog(null, "Login cancelled"); return; }
                host = auth[0]; portStr = auth[1]; id = auth[2]; c.clientPassword = auth[3];
                int port = 5000; try { port = Integer.parseInt(portStr); } catch (Exception ignored) {}
                ok = c.connect(host, port, id);
            } else {
                // auto connect path (no dialog) - useful when server does not require auth
                host = AUTO_CONNECT_HOST; int port = AUTO_CONNECT_PORT;
                if ("__PROMPT__".equals(AUTO_CONNECT_ID)) {
                    id = JOptionPane.showInputDialog(null, "Enter username / student id:", "Login", JOptionPane.PLAIN_MESSAGE);
                    if (id == null || id.trim().isEmpty()) { JOptionPane.showMessageDialog(null, "No id provided"); return; }
                } else id = AUTO_CONNECT_ID;
                ok = c.connect(host, port, id);
            }
            if (!ok) JOptionPane.showMessageDialog(null, "Unable to connect to server");
        });
    }

    // Create and connect a Socket (plain or SSL) by trying all DNS addresses for host.
    private Socket createSocket(String host, int port, int timeout, List<String> errors) throws IOException {
        java.net.InetAddress[] addrs = java.net.InetAddress.getAllByName(host);
        appendSystem("Resolved " + addrs.length + " addresses. Trying to connect to port " + port + "...");
        for (java.net.InetAddress addr : addrs) {
            String a = addr.getHostAddress(); appendSystem("Attempting " + a + ":" + port);
            try {
                if (ENABLE_SSL_CLIENT) {
                    System.setProperty("javax.net.ssl.trustStore", SSL_TRUSTSTORE);
                    System.setProperty("javax.net.ssl.trustStorePassword", SSL_TRUSTSTORE_PASS);
                    SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
                    SSLSocket ss = (SSLSocket) sf.createSocket();
                    ss.connect(new java.net.InetSocketAddress(addr, port), timeout);
                    ss.startHandshake();
                    return ss;
                } else {
                    java.net.Socket s = new java.net.Socket();
                    s.connect(new java.net.InetSocketAddress(addr, port), timeout);
                    return s;
                }
            } catch (Exception ex) {
                errors.add(a + ": " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                appendSystem("Attempt to " + a + " failed: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        }
        throw new IOException("All addresses failed: " + String.join("; ", errors));
    }

    // Show a compact login dialog that collects host, port, username and password
    // This dialog is used when ENABLE_AUTH_CLIENT == true. It returns {host,port,user,pass} or null if cancelled.
    private String[] showAuthDialog() {
        JPanel p = new JPanel(new GridLayout(0,2));
        JTextField hostF = new JTextField("localhost");
        JTextField portF = new JTextField("5000");
        JTextField userF = new JTextField();
        JPasswordField passF = new JPasswordField();
        p.add(new JLabel("Server host:")); p.add(hostF);
        p.add(new JLabel("Server port:")); p.add(portF);
        p.add(new JLabel("Username:")); p.add(userF);
        p.add(new JLabel("Password:")); p.add(passF);
        int ok = JOptionPane.showConfirmDialog(frame, p, "Login", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return null;
        return new String[] { hostF.getText().trim(), portF.getText().trim(), userF.getText().trim(), new String(passF.getPassword()) };
    }
}
