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

    // --- Trạng thái server ---
    private ServerSocket serverSocket;                       // socket lắng nghe
    private boolean isRunning = false;                       // cờ trạng thái server
    private final List<ClientHandler> clients = Collections.synchronizedList(new ArrayList<>());
                                                          // danh sách handler (đồng bộ)
    private int clientCounter = 0;                           // bộ đếm client để đặt id

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
            serverSocket = new ServerSocket(PORT);
            serverSocket.setReuseAddress(true);
            isRunning = true;

            btnStart.setEnabled(false);
            btnStop.setEnabled(true);
            lblStatus.setText("Status: Running on port " + PORT);
            lblStatus.setForeground(new Color(0, 150, 0));

            log("╔════════════════════════════════════════╗");
            log("║       SERVER STARTED (UNICAST)        ║");
            log("╚════════════════════════════════════════╝");
            log("Port: " + PORT);
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
