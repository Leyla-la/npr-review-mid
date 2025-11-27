package updatedmid2025unicast;
import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.util.*;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.*; // for optional TLS support
import java.security.*; // for security exceptions

/*
 BCrypt example (commented):
 This block shows how to use BCrypt (jBCrypt) instead of SHA-256 for password hashing.
 It's commented so it doesn't affect runtime. To enable, add jBCrypt to your classpath.

 // import org.mindrot.jbcrypt.BCrypt;
 // private static String bcryptHash(String password) {
 //     return BCrypt.hashpw(password, BCrypt.gensalt(12));
 // }
 // private static boolean bcryptVerify(String plain, String storedHash) {
 //     return BCrypt.checkpw(plain, storedHash);
 // }
 */

/*
 Server.java
 - Đây là lớp GUI + server TCP dạng unicast.
 - Trách nhiệm chính:
   * Hiển thị giao diện (start/stop server, log, danh sách client)
   * Lắng nghe kết nối client và tạo một ClientHandler cho mỗi client
   * Quản lý thư mục lưu file upload
 - Lưu ý: mỗi client được xử lý trong một thread riêng bằng ClientHandler
 */
public class Server extends JFrame {
    // Logger để ghi lại lỗi nghiêm trọng thay vì dùng printStackTrace
    private static final Logger LOGGER = Logger.getLogger(Server.class.getName());
    // --- Cấu hình server ---
    private static final int PORT = 4075;                    // port lắng nghe
    private static final String UPLOAD_DIR = "server_files_unicast/"; // thư mục lưu file upload

    // --- Thành phần GUI ---
    private JTextArea txtLog;         // vùng hiển thị log server
    private JButton btnStart;         // nút start server
    private JButton btnStop;          // nút stop server
    private JLabel lblStatus;         // trạng thái server (Running/Stopped)
    private JLabel lblClientCount;    // hiển thị số client đang kết nối
    private DefaultListModel<String> clientListModel; // model cho danh sách client
    private JList<String> clientList; // component hiển thị danh sách client
    private JCheckBox chkTLS;         // checkbox bật/tắt TLS

    // --- Trạng thái server ---
    private ServerSocket serverSocket;                       // socket lắng nghe
    private boolean isRunning = false;                       // cờ trạng thái server
    private final List<ClientHandler> clients = Collections.synchronizedList(new ArrayList<>());
                                                          // danh sách handler (đồng bộ)
    private int clientCounter = 0;                           // bộ đếm client để đặt id

    // --- New features: per-user bank accounts, poll manager, and helper utilities ---
    // Map studentId -> BankAccount (synchronized)
    private final Map<String, BankAccount> accounts = Collections.synchronizedMap(new HashMap<>());

    // Polls: pollId -> Poll
    private final Map<Integer, Poll> polls = Collections.synchronizedMap(new HashMap<>());
    private final java.util.concurrent.atomic.AtomicInteger pollCounter = new java.util.concurrent.atomic.AtomicInteger(0);

    // Helper method to broadcast a text message to all connected clients
    private void broadcastToAll(String message) {
        synchronized (clients) {
            for (ClientHandler ch : clients) {
                try {
                    if (ch != null && ch.writer != null) {
                        ch.writer.write(message);
                        ch.writer.newLine();
                        ch.writer.flush();
                    }
                } catch (IOException ignore) {}
            }
        }
    }

    // Simple BankAccount class (per-user). Uses BigInteger and keeps a small history list.
    private static class BankAccount {
        private java.math.BigInteger balance = java.math.BigInteger.ZERO;
        private final List<String> history = new ArrayList<>();

        public synchronized java.math.BigInteger deposit(java.math.BigInteger amount) {
            balance = balance.add(amount);
            String entry = "DEPOSIT:" + amount + ":BAL=" + balance;
            history.add(entry);
            return balance;
        }

        public synchronized java.math.BigInteger withdraw(java.math.BigInteger amount) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 10_000; // 10s wait
            while (balance.compareTo(amount) < 0) {
                long waitTime = deadline - System.currentTimeMillis();
                if (waitTime <= 0) throw new IllegalStateException("Insufficient");
                wait(waitTime);
            }
            balance = balance.subtract(amount);
            String entry = "WITHDRAW:" + amount + ":BAL=" + balance;
            history.add(entry);
            return balance;
        }

        public synchronized java.math.BigInteger getBalance() { return balance; }
        public synchronized List<String> getHistory() { return new ArrayList<>(history); }
    }

    // Simple Poll class to manage votes
    private static class Poll {
        final int id;
        final String title;
        final List<String> options;
        final Map<Integer, Integer> votes = new HashMap<>(); // optionIndex -> count

        Poll(int id, String title, List<String> options) {
            this.id = id; this.title = title; this.options = options;
            for (int i = 0; i < options.size(); i++) votes.put(i, 0);
        }

        public synchronized void vote(int optIndex) {
            votes.put(optIndex, votes.getOrDefault(optIndex, 0) + 1);
        }

        public synchronized String resultsString() {
            StringBuilder sb = new StringBuilder();
            sb.append("POLL_RESULT:").append(id).append(":").append(title).append(":");
            for (int i = 0; i < options.size(); i++) {
                sb.append(options.get(i)).append("=").append(votes.getOrDefault(i,0));
                if (i < options.size()-1) sb.append(",");
            }
            return sb.toString();
        }

        public synchronized String optionsCSV() {
            return String.join(",", options);
        }
    }

    /*
     Constructor
     - Khởi tạo GUI
     - Kiểm tra/ tạo thư mục upload nếu cần
    */
    public Server() {
        initializeGUI();
        File dir = new File(UPLOAD_DIR);
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            if (!created) {
                // Trường hợp không tạo được thư mục: log ra stderr (GUI chưa sẵn sàng để log)
                System.err.println("Warning: cannot create upload directory: " + UPLOAD_DIR);
            }
        }
    }

    // --- Phần tạo GUI ---
    // create initializeGUI() builds the main window: control panel + split pane (log + client list)
    private void initializeGUI() {
        setTitle("TCP Server - Unicast Mode");
        setSize(900, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        mainPanel.add(createControlPanel(), BorderLayout.NORTH);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setLeftComponent(createLogPanel());
        splitPane.setRightComponent(createClientListPanel());
        splitPane.setDividerLocation(600);
        mainPanel.add(splitPane, BorderLayout.CENTER);

        add(mainPanel);

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                stopServer(); // đảm bảo server được dừng khi đóng cửa sổ
            }
        });
    }

    /*
     Tạo control panel (Start/Stop + status)
     - btnStart: bắt đầu lắng nghe kết nối
     - btnStop: dừng server, đóng các client handler
    */
    private JPanel createControlPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Server Control"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0;
        btnStart = new JButton("▶ Start Server");
        btnStart.setFont(new Font("Arial", Font.BOLD, 12));
        btnStart.addActionListener(e -> startServer());
        panel.add(btnStart, gbc);

        gbc.gridx = 1;
        btnStop = new JButton("⏹ Stop Server");
        btnStop.setFont(new Font("Arial", Font.BOLD, 12));
        btnStop.setEnabled(false);
        btnStop.addActionListener(e -> stopServer());
        panel.add(btnStop, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2;
        lblStatus = new JLabel("Status: Stopped");
        lblStatus.setForeground(Color.RED);
        lblStatus.setFont(new Font("Arial", Font.BOLD, 14));
        panel.add(lblStatus, gbc);

        gbc.gridy = 2;
        lblClientCount = new JLabel("Connected Clients: 0");
        lblClientCount.setFont(new Font("Arial", Font.PLAIN, 12));
        panel.add(lblClientCount, gbc);

        gbc.gridy = 3;
        chkTLS = new JCheckBox("Enable TLS");
        chkTLS.setFont(new Font("Arial", Font.PLAIN, 12));
        panel.add(chkTLS, gbc);

        return panel;
    }

    /*
     Tạo panel log
     - txtLog: vùng hiển thị các message, status, progress
    */
    private JPanel createLogPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Server Log"));

        txtLog = new JTextArea();
        txtLog.setEditable(false);
        // Use Times New Roman for server logs per user request
        txtLog.setFont(new Font("Times New Roman", Font.PLAIN, 12));
        txtLog.setBackground(new Color(240, 240, 240));
        JScrollPane scrollPane = new JScrollPane(txtLog);
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    /*
     Tạo panel danh sách client
     - clientListModel + clientList dùng để hiển thị các client đang kết nối
     - getDisplayInfo() của mỗi ClientHandler được đưa vào model
    */
    private JPanel createClientListPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Connected Clients"));

        clientListModel = new DefaultListModel<>();
        clientList = new JList<>(clientListModel);
        clientList.setFont(new Font("Consolas", Font.PLAIN, 11));
        JScrollPane scrollPane = new JScrollPane(clientList);
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    /*
     startServer()
     - Mở ServerSocket và khởi động thread chính để accept connections
     - Với mỗi kết nối: tạo ClientHandler, thêm vào danh sách và start thread handler
     - Cập nhật GUI (status, client list)
    */
    private void startServer() {
        try {
            // If TLS checkbox is selected, set SSL system properties (uses secure.txt defaults)
            boolean useTLS = chkTLS != null && chkTLS.isSelected();
            if (useTLS) {
                // These defaults match the secure.txt example; change files/passwords as needed
                System.setProperty("javax.net.ssl.keyStore", "./SSLStore");
                System.setProperty("javax.net.ssl.keyStorePassword", "123456");
                System.setProperty("javax.net.ssl.keyStoreType", "JKS");
            }

            // Create the appropriate server socket (SSL if requested)
            if (useTLS) {
                try {
                    SSLServerSocketFactory ssf = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
                    serverSocket = ssf.createServerSocket(PORT);
                    log("Using TLS server socket (JSSE) on port " + PORT);
                } catch (Exception e) {
                    // TLS init failed: log and ask whether to fallback to plaintext
                    log("✗ TLS initialization failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    e.printStackTrace();
                    int r = JOptionPane.showConfirmDialog(this,
                            "TLS initialization failed:\n" + e.getMessage() + "\nStart server without TLS instead?",
                            "TLS Initialization Error", JOptionPane.YES_NO_OPTION);
                    if (r == JOptionPane.YES_OPTION) {
                        serverSocket = new ServerSocket(PORT);
                        log("FALLBACK: Plain ServerSocket running on port " + PORT);
                    } else {
                        log("Server not started (TLS required but failed)");
                        return;
                    }
                }
            } else {
                serverSocket = new ServerSocket(PORT);
            }

            serverSocket.setReuseAddress(true);
            isRunning = true;

            btnStart.setEnabled(false);
            btnStop.setEnabled(true);
            lblStatus.setText("Status: Running on port " + PORT + (useTLS?" (TLS)":""));
            lblStatus.setForeground(new Color(0, 150, 0));

            log("╔════════════════════════════════════════╗");
            log("║       SERVER STARTED (UNICAST)        ║");
            log("╚════════════════════════════════════════╝");
            log("Port: " + PORT + (useTLS?" (TLS)":""));
            log("Upload directory: " + UPLOAD_DIR);
            log("Waiting for clients...\n");

            new Thread(() -> {
                while (isRunning) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        String clientId = "Client #" + (++clientCounter);
                        String clientInfo = clientSocket.getInetAddress().getHostAddress() + ":" +
                                clientSocket.getPort();

                        ClientHandler handler = new ClientHandler(clientSocket, clientId, clientInfo);
                        clients.add(handler);
                        new Thread(handler).start();

                        log("✓ " + clientId + " [" + clientInfo + "] connected");
                        updateClientList();

                    } catch (SocketException se) {
                        if (isRunning) log("✗ Error accepting client: " + se.getMessage());
                        break;
                    } catch (IOException e) {
                        if (isRunning) {
                            log("✗ Error accepting client: " + e.getMessage());
                        }
                    }
                }
            }).start();

        } catch (IOException e) {
            log("✗ Server start error: " + e.getMessage());
            JOptionPane.showMessageDialog(this,
                    "Cannot start server!\n" + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /*
     stopServer()
     - Dừng server: đóng tất cả ClientHandler, đóng ServerSocket
     - Cập nhật GUI
    */
    private void stopServer() {
        isRunning = false;

        try {
            synchronized (clients) {
                for (ClientHandler client : clients) {
                    client.close();
                }
                clients.clear();
            }

            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }

            btnStart.setEnabled(true);
            btnStop.setEnabled(false);
            lblStatus.setText("Status: Stopped");
            lblStatus.setForeground(Color.RED);

            log("\n╔════════════════════════════════════════╗");
            log("║          SERVER STOPPED               ║");
            log("╚════════════════════════════════════════╝\n");
            updateClientList();

        } catch (IOException e) {
            log("✗ Error stopping server: " + e.getMessage());
        }
    }

    /*
     updateClientList()
     - Cập nhật model của JList để hiển thị client hiện đang kết nối
     - Gọi từ nhiều thread, nên dùng SwingUtilities.invokeLater để an toàn với EDT
    */
    private void updateClientList() {
        SwingUtilities.invokeLater(() -> {
            clientListModel.clear();
            synchronized (clients) {
                for (ClientHandler client : clients) {
                    clientListModel.addElement(client.getDisplayInfo());
                }
            }
            lblClientCount.setText("Connected Clients: " + clients.size());
        });
    }

    /*
     log(message)
     - Ghi message vào vùng txtLog (sử dụng invokeLater để chạy trên EDT)
    */
    private void log(String message) {
        SwingUtilities.invokeLater(() -> {
            txtLog.append(message + "\n");
            txtLog.setCaretPosition(txtLog.getDocument().getLength());
        });
    }

    /*
     ClientHandler
     - Lớp xử lý riêng cho từng client kết nối
     - Trách nhiệm:
       * đọc Student_ID ban đầu và phản hồi (4×ID)
       * lắng nghe lệnh từ client: QUIT, FILE, hoặc tin nhắn (xử lý số mũ 4)
       * xử lý upload file theo framed protocol (int length + data), hỗ trợ CANCEL (-1)
    */
    private class ClientHandler implements Runnable {
        private Socket socket;
        private String clientId;
        private String clientInfo;
        private String studentId;
        private BufferedReader reader;
        private BufferedWriter writer;
        private DataInputStream dataIn;
        private DataOutputStream dataOut;

        ClientHandler(Socket socket, String clientId, String clientInfo) {
            this.socket = socket;
            this.clientId = clientId;
            this.clientInfo = clientInfo;
            try {
                // Mỗi handler giữ riêng streams của mình (không chia sẻ)
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
                dataIn = new DataInputStream(socket.getInputStream());
                dataOut = new DataOutputStream(socket.getOutputStream());
            } catch (IOException e) {
                log("✗ Error initializing " + clientId + ": " + e.getMessage());
            }
        }

        // getDisplayInfo: dùng để show danh sách client trên GUI
        String getDisplayInfo() {
            return clientId + " [" + studentId + "] - " + clientInfo;
        }

        @Override
        public void run() {
            try {
                // Nhận Student_ID đầu tiên (protocol yêu cầu client gửi ID sau khi connect)
                studentId = reader.readLine();
                if (studentId != null && !studentId.isEmpty()) {
                    log(">> " + clientId + " sent Student_ID: " + studentId);

                    String response = calculateFirstResponse(studentId);
                    writer.write(response);
                    writer.newLine();
                    writer.flush();
                    log("<< Sent to " + clientId + ": 4×" + studentId + " = " + response);

                    updateClientList();
                }

                // Vòng lặp xử lý các lệnh/tin nhắn từ client
                String command;
                while ((command = reader.readLine()) != null) {
                    log("\n>> " + clientId + " command: " + command);

                    // New: support colon-prefixed commands like BANK:DEPOSIT:5000, POLL:CREATE:title:opt1,opt2, SAVELOG, LIST
                    if (command.startsWith("BANK:")) {
                        handleBankCommand(command);
                        continue;
                    } else if (command.startsWith("POLL:")) {
                        handlePollCommand(command);
                        continue;
                    } else if (command.equals("SAVELOG")) {
                        handleSaveLog();
                        continue;
                    }

                    if ("QUIT".equals(command)) {
                        // client muốn ngắt kết nối
                        log("✓ " + clientId + " requested disconnect");
                        break;
                    }
                    else if ("FILE".equals(command)) {
                        // client chuẩn bị gửi file theo framed protocol
                        handleFileUpload();
                    }
                    else {
                        // trường hợp gửi message; server trả về echo hoặc số mũ 4
                        handleMessage(command);
                    }
                }
            } catch (IOException e) {
                log("✗ " + clientId + " error: " + e.getMessage());
            } finally {
                // Cleanup khi client disconnect hoặc có lỗi
                close();
                clients.remove(this);
                log("✗ " + clientId + " disconnected\n");
                updateClientList();
            }
        }

        // --------- New handlers: BANK, POLL, SAVELOG ----------
        private void handleBankCommand(String command) throws IOException {
            // BANK:DEPOSIT:5000  or BANK:WITHDRAW:3000 or BANK:BAL
            String[] parts = command.split(":", 3);
            String op = parts.length >=2 ? parts[1].toUpperCase() : "";
            accounts.putIfAbsent(studentId, new BankAccount());
            BankAccount acc = accounts.get(studentId);

            if ("BAL".equals(op)) {
                writer.write("BANK_RES:BAL:" + acc.getBalance().toString()); writer.newLine(); writer.flush();
            } else if ("DEPOSIT".equals(op) && parts.length==3) {
                try {
                    java.math.BigInteger amt = new java.math.BigInteger(parts[2].trim());
                    java.math.BigInteger newBal = acc.deposit(amt);
                    writer.write("BANK_RES:DEPOSIT:" + newBal.toString()); writer.newLine(); writer.flush();
                    // also send small history entry to this client
                    writer.write("BANK_HISTORY:" + studentId + ":DEPOSIT:" + amt + ":BAL:" + newBal); writer.newLine(); writer.flush();
                    // broadcast balance change to all
                    broadcastToAll("BANK_BC:" + studentId + ":balance:" + newBal.toString());
                } catch (Exception e) { writer.write("BANK_ERR:invalid amount"); writer.newLine(); writer.flush(); }
            } else if ("WITHDRAW".equals(op) && parts.length==3) {
                try {
                    java.math.BigInteger amt = new java.math.BigInteger(parts[2].trim());
                    try {
                        java.math.BigInteger newBal = acc.withdraw(amt);
                        writer.write("BANK_RES:WITHDRAW:" + newBal.toString()); writer.newLine(); writer.flush();
                        writer.write("BANK_HISTORY:" + studentId + ":WITHDRAW:" + amt + ":BAL:" + newBal); writer.newLine(); writer.flush();
                        broadcastToAll("BANK_BC:" + studentId + ":balance:" + newBal.toString());
                    } catch (IllegalStateException | InterruptedException ise) {
                        writer.write("BANK_ERR:insufficient funds"); writer.newLine(); writer.flush();
                    }
                } catch (Exception e) { writer.write("BANK_ERR:invalid amount"); writer.newLine(); writer.flush(); }
            } else {
                writer.write("BANK_ERR:unknown"); writer.newLine(); writer.flush();
            }
        }

        private void handlePollCommand(String command) throws IOException {
            // POLL:CREATE:title:opt1,opt2  or POLL:VOTE:pollId:optIndex or POLL:LIST
            String[] p = command.split(":", 4);
            String op = p.length>=2 ? p[1].toUpperCase() : "";
            if ("CREATE".equals(op) && p.length>=4) {
                String title = p[2];
                String[] opts = p[3].split(",");
                int id = pollCounter.incrementAndGet();
                Poll pol = new Poll(id, title, java.util.Arrays.asList(opts));
                polls.put(id, pol);
                // broadcast new poll
                broadcastToAll("POLL_NEW:" + id + ":" + title + ":" + pol.optionsCSV());
                writer.write("POLL_CREATED:" + id); writer.newLine(); writer.flush();
            } else if ("VOTE".equals(op) && p.length>=4) {
                try {
                    int pid = Integer.parseInt(p[2]);
                    int opt = Integer.parseInt(p[3]);
                    Poll pol = polls.get(pid);
                    if (pol != null) {
                        pol.vote(opt);
                        // broadcast updated results
                        broadcastToAll(pol.resultsString());
                        writer.write("POLL_VOTED:" + pid); writer.newLine(); writer.flush();
                    } else { writer.write("POLL_ERR:notfound"); writer.newLine(); writer.flush(); }
                } catch (NumberFormatException nfe) { writer.write("POLL_ERR:badargs"); writer.newLine(); writer.flush(); }
            } else if ("LIST".equals(op)) {
                // list all polls
                StringBuilder sb = new StringBuilder();
                synchronized (polls) {
                    for (Poll pol : polls.values()) {
                        sb.append(pol.id).append("|").append(pol.title).append("|").append(pol.optionsCSV()).append(";;");
                    }
                }
                writer.write("POLL_LIST:" + sb.toString()); writer.newLine(); writer.flush();
            } else {
                writer.write("POLL_ERR:unknown"); writer.newLine(); writer.flush();
            }
        }

        private void handleSaveLog() throws IOException {
            // Read chat_log.txt if exists and send as base64 line: SAVELOGDATA:filename:b64
            java.nio.file.Path logPath = java.nio.file.Paths.get("chat_log.txt");
            if (java.nio.file.Files.exists(logPath)) {
                byte[] bytes = java.nio.file.Files.readAllBytes(logPath);
                String b64 = java.util.Base64.getEncoder().encodeToString(bytes);
                String fname = logPath.getFileName().toString();
                writer.write("SAVELOGDATA:" + fname + ":" + b64);
                writer.newLine(); writer.flush();
                log("<< Sent SAVELOG to " + clientId);
            } else {
                writer.write("SAVELOG_ERR:missing"); writer.newLine(); writer.flush();
            }
        }
        // --------- end new handlers ----------


        /*
         calculateFirstResponse(id)
         - Nếu id là số, trả 4×id; nếu không, trả lỗi
        */
        private String calculateFirstResponse(String id) {
            try {
                return new BigInteger(id).multiply(BigInteger.valueOf(4)).toString();
            } catch (NumberFormatException e) {
                return "ERROR: Invalid Student ID";
            }
        }

        /*
         handleMessage(msg)
         - Xử lý tin nhắn thường: nếu msg là số >0, gửi num^4; nếu không, echo lại
         - Ghi log tương ứng
        */
        private void handleMessage(String msg) throws IOException {
            String response = processMessage(msg);
            writer.write(response);
            writer.newLine();
            writer.flush();

            try {
                BigInteger num = new BigInteger(msg);
                if (num.compareTo(BigInteger.ZERO) > 0) {
                    log("<< Sent to " + clientId + ": [" + msg + "]^4 = " + response);
                } else {
                    log("<< Sent to " + clientId + ": (echo) " + response);
                }
            } catch (NumberFormatException e) {
                log("<< Sent to " + clientId + ": (echo) " + response);
            }
        }

        /*
         processMessage(msg)
         - Trả về num^4 nếu msg là số (>0), ngược lại trả về msg (echo)
        */
        private String processMessage(String msg) {
            try {
                BigInteger num = new BigInteger(msg);
                if (num.compareTo(BigInteger.ZERO) > 0) {
                    return num.pow(4).toString();
                } else {
                    return msg;
                }
            } catch (NumberFormatException e) {
                return msg;
            }
        }


        /*
         handleFileUpload()
         - Đọc fileName (UTF) và fileSize (long)
         - Đọc các frame: mỗi frame bắt đầu bằng một int (độ dài)
             * -1 => CLIENT CANCELLED (không đọc thêm), server xóa partial và trả "CANCELLED"
             * 0  => EOF (kết thúc gửi thành công)
             * >0 => đọc đúng số byte đó và ghi vào file
         - Sau khi hoàn thành, nếu tổng byte nhận == fileSize => trả "SUCCESS"; ngược lại xóa partial và trả lỗi
         - Xử lý các trường hợp EOF bất ngờ, lỗi IO
         - Ghi log tiến trình mỗi ~500ms
        */
        private void handleFileUpload() throws IOException {
            try {
                String fileName = dataIn.readUTF();
                long fileSize = dataIn.readLong();

                log("  >> Receiving file: " + fileName);
                log("    Size: " + formatFileSize(fileSize));

                String savePath = UPLOAD_DIR + clientId + "_" + fileName;

                long totalReceived = 0;
                boolean cancelled = false;
                long lastLogTime = System.currentTimeMillis();

                try (FileOutputStream fos = new FileOutputStream(savePath)) {
                    byte[] buffer = new byte[4096];

                    while (true) {
                        int frameLen;
                        try {
                            frameLen = dataIn.readInt();
                        } catch (EOFException eof) {
                            // client disconnected unexpectedly
                            cancelled = true;
                            log("    (EOF) Client disconnected while sending file");
                            break;
                        }

                        if (frameLen == -1) {
                            // client requested cancel
                            cancelled = true;
                            log("    Client sent CANCEL frame");
                            break;
                        }

                        if (frameLen == 0) {
                            // finished sending
                            break;
                        }

                        int remaining = frameLen;
                        while (remaining > 0) {
                            int toRead = Math.min(remaining, buffer.length);
                            int actuallyRead = dataIn.read(buffer, 0, toRead);
                            if (actuallyRead == -1) {
                                cancelled = true;
                                log("    Unexpected EOF while reading chunk");
                                break;
                            }
                            fos.write(buffer, 0, actuallyRead);
                            totalReceived += actuallyRead;
                            remaining -= actuallyRead;

                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastLogTime > 500 || totalReceived == fileSize) {
                                int progress = (fileSize > 0) ? (int)((totalReceived * 100) / fileSize) : 0;
                                log("    Progress: " + progress + "% (" + formatFileSize(totalReceived) +
                                        " / " + formatFileSize(fileSize) + ")");
                                lastLogTime = currentTime;
                            }
                        }

                        if (cancelled) break;
                    }
                    fos.getFD().sync();
                }

                if (cancelled || totalReceived != fileSize) {
                    // delete partial file if exists
                    File partial = new File(savePath);
                    if (partial.exists()) {
                        if (!partial.delete()) {
                            log("    Warning: failed to delete partial file: " + savePath);
                        }
                    }

                    try {
                        if (cancelled) {
                            dataOut.writeUTF("CANCELLED");
                        } else {
                            dataOut.writeUTF("ERROR: Incomplete upload");
                        }
                        dataOut.flush();
                    } catch (IOException ignored) {
                        // client may have disconnected
                    }

                    log("  ✗ File upload incomplete or cancelled: " + savePath + "\n");
                } else {
                    try {
                        dataOut.writeUTF("SUCCESS");
                        dataOut.flush();
                    } catch (IOException ignored) {
                        // client likely disconnected
                    }
                    log("  ✓ File saved successfully: " + savePath + "\n");
                }

            } catch (IOException e) {
                log("  ✗ File upload error: " + e.getMessage());
                try {
                    dataOut.writeUTF("ERROR: " + e.getMessage());
                    dataOut.flush();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }

        // formatFileSize: helper để format kích thước file cho log
        private String formatFileSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        }

        // close: đóng các stream và socket của client handler
        void close() {
            try {
                if (reader != null) reader.close();
                if (writer != null) writer.close();
                if (dataIn != null) dataIn.close();
                if (dataOut != null) dataOut.close();
                if (socket != null && !socket.isClosed()) socket.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    // main: tạo và hiển thị GUI server
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                // Ghi lỗi qua Logger thay vì in stack trace trực tiếp
                LOGGER.log(Level.SEVERE, "Failed to set system look and feel", e);
            }
            new Server().setVisible(true);
        });
    }
}
